package DataFlowScheduler

import chisel3._
import chisel3.util._

import chext.elastic
import chext.elastic.ConnectOp._

import scala.annotation.nowarn

/** Configuration of a [[DataFlowTaskUnit]].
  *
  * @param wData
  *   Payload width. It is shared by both inputs and both outputs.
  * @param inputBias
  *   Arbitration bias towards the high probability input, see [[chext.elastic.BiasedChooser]]. It
  *   is the maximum number of back-to-back grants the primary input may take while the secondary
  *   one is waiting.
  * @param inputDepth
  *   Depth of the input registers. 2 (a skid buffer) is the smallest depth that fully cuts both the
  *   valid and the ready path while sustaining II=1.
  * @param computeDepth
  *   Extra pipeline depth inserted after the payload transform. 0 by default: the merge stage
  *   already registers the stream, so a combinational transform needs no additional stage. Raise it
  *   to 2 if `computeFn` becomes timing critical.
  *
  * The output registers are not configurable: the routing stage chooses on sink readiness, so its
  * skid buffers are part of its decision logic and are the 2-entry ones that
  * [[chext.elastic.Distributor]] instantiates itself.
  */
case class DataFlowTaskUnitConfig(
    wData: Int,
    inputBias: Int = 4,
    inputDepth: Int = 2,
    computeDepth: Int = 0
) {
  require(wData > 0, "payload must be at least 1 bit wide")
  require(inputBias >= 1, "the primary input must be able to win at least once")
  require(inputDepth >= 2, "a depth of 2 is required to register both the valid and the ready path")
  require(computeDepth == 0 || computeDepth >= 2, "an elastic pipeline stage needs a depth of 2")
}

class DataFlowTaskUnitIO(cfg: DataFlowTaskUnitConfig) extends Bundle {
  import cfg._

  /** High probability input. */
  val s_primary = Flipped(Decoupled(UInt(wData.W)))

  /** Low probability input. */
  val s_secondary = Flipped(Decoupled(UInt(wData.W)))

  /** High probability output: taken whenever it can accept the token. */
  val m_primary = Decoupled(UInt(wData.W))

  /** Low probability output: taken when the primary one is backpressuring. */
  val m_secondary = Decoupled(UInt(wData.W))
}

/** A streaming task unit of a data flow network.
  *
  * The unit merges two elastic inputs into a single token stream, applies a payload transform to
  * it, and hands every token to one of two elastic outputs. Both inputs and both outputs carry the
  * same, configurable, payload width.
  *
  * The unit is skewed towards the streams that are expected to be the busy ones:
  *   - `s_primary` is granted back-to-back by a [[chext.elastic.BiasedChooser]], so the common case
  *     never pays for arbitration; `cfg.inputBias` bounds how long `s_secondary` may be starved.
  *   - `m_primary` is the preferred output. The routing stage does not look at the payload: it
  *     hands each token to whichever output can take it, and to `m_primary` whenever both can. So
  *     `m_secondary` only carries what `m_primary` could not absorb.
  *
  * Because the choice follows readiness rather than the token, a backpressuring output does not
  * block the other one: the stream only stalls when *both* outputs are full.
  *
  * Throughput is II=1 end to end: every stage accepts and produces one token per cycle as long as
  * the neighbouring stage does not backpressure.
  *
  * Registering is kept to the minimum that still allows a 300 MHz closure: a 2-entry skid buffer at
  * each input, the register the merge stage already carries at its output, and the 2-entry skid
  * buffers the routing stage keeps at each output. A skid buffer is the shallowest elastic register
  * that breaks *both* the valid and the ready path, so no combinational path crosses the unit
  * boundary and the longest path inside the unit is a 2-way mux plus the (user supplied) transform.
  *
  * @param cfg
  *   Unit configuration.
  * @param computeFn
  *   Payload transform, `(in, out)`. Defaults to the identity.
  * @param outputChooserFn
  *   Policy used to pick among the ready outputs. Defaults to a strict priority, i.e. `m_primary`
  *   wins whenever it is ready.
  */
class DataFlowTaskUnit(
    val cfg: DataFlowTaskUnitConfig,
    val computeFn: (UInt, UInt) => Unit = (in: UInt, out: UInt) => { out := in },
    val outputChooserFn: elastic.Chooser.ChooserFn = elastic.Chooser.priority(_)
) extends Module {
  import cfg._
  import DataFlowTaskUnit.{Primary, Secondary}

  override def desiredName: String = "dataFlowTaskUnit"

  private val gen = UInt(wData.W)

  val io = IO(new DataFlowTaskUnitIO(cfg))

  // Stage 0: register the inputs, so that neither valid nor ready crosses the unit boundary
  // combinationally.
  private val s_primary = elastic.SourceBuffer(io.s_primary, inputDepth)
  private val s_secondary = elastic.SourceBuffer(io.s_secondary, inputDepth)

  // Stage 1: merge the two inputs into a single stream, biased towards the primary one.
  // BasicArbiter registers its own sink, which is the pipeline register of this stage.
  private val merge = Module(
    new elastic.BasicArbiter(gen, 2, elastic.BiasedChooser(Primary, inputBias))
  )

  s_primary :=> merge.io.sources(Primary)
  s_secondary :=> merge.io.sources(Secondary)

  // The token itself carries where it came from, if that matters, so the grant stream is dropped.
  elastic.Disposed(merge.io.select)

  // Stage 2: payload transform, plus an optional pipeline stage for a timing critical transform.
  private val computed = Wire(elastic.Interface(gen))

  @nowarn("cat=unused")
  private val transform0 = new elastic.Transform(merge.io.sink, computed) {
    protected def onTransform: Unit = computeFn(in, out)
  }

  private val routed = elastic.SourceBuffer(computed, computeDepth)

  // Stage 3: hand the token to whichever output can take it, preferring the primary one. The
  // Distributor chooses on the readiness of its own (2-entry) sink buffers, which is a registered
  // "not full" flag rather than the downstream ready, so no combinational path runs from an output
  // ready back into the pipeline.
  private val split = Module(new elastic.Distributor(gen, 2, outputChooserFn))

  routed :=> split.io.source

  // The choice is a scheduling decision, not information the network needs downstream.
  elastic.Disposed(split.io.select)

  split.io.sinks(Primary) :=> io.m_primary
  split.io.sinks(Secondary) :=> io.m_secondary
}

object DataFlowTaskUnit {

  /** Index of the high probability input/output. */
  val Primary = 0

  /** Index of the low probability input/output. */
  val Secondary = 1
}
