package Scheduler

import chisel3._
import chisel3.util._
import Util._

// The interface connection for the stealing network data unit.
class SchedulerNetworkDataUnitIO(taskWidth: Int, elastic: Boolean) extends Bundle {
  // Connections to other network data units (Shift register and not elastic)
  val taskIn = Input(UInt(taskWidth.W))
  val taskOut = Output(UInt(taskWidth.W))

  val validIn = Input(Bool())
  val validOut = Output(Bool())

  // Elastic-ring backpressure, carried as TWO bits because the upstream hop has
  // to be able to tell the two reasons apart:
  //
  //   Full -- hard. There is physically nowhere to put another task. Overriding
  //     this would drop one, so nothing may.
  //   Want -- soft. A local producer would like this slot. A preference, and an
  //     upstream hop holding forceForward may ignore it.
  //
  // Present only on an elastic ring; a rigid ring does not carry them at all.
  val stopInFull = if (elastic) Some(Input(Bool())) else None
  val stopInWant = if (elastic) Some(Input(Bool())) else None
  val stopOutFull = if (elastic) Some(Output(Bool())) else None
  val stopOutWant = if (elastic) Some(Output(Bool())) else None

  // "Push through a soft stop this cycle", from the local producer. Driven off
  // registered producer state (SpawnerServer.atCapacity), so it does not lengthen
  // the ring path.
  val forceForward = if (elastic) Some(Input(Bool())) else None

  // "My local producer wants to inject here", taken straight from that producer
  // rather than from connSS.qOutTask.valid.  They are the same signal at most
  // nodes, but not where a SchedulerInjectionSwap sits between the producer and
  // the ring: the swap derives its valid from BOTH slots' ready, so reading
  // qOutTask.valid here would make stopOut depend on qOutTask.ready and close a
  // combinational loop around the ring.  Reading the producer keeps stopOut on
  // registered/local state, which is the whole reason the ring is safe to close.
  val injectWanted = if (elastic) Some(Input(Bool())) else None

  // Connections to steal server
  val connSS = new SchedulerNetworkClientData(taskWidth)

  val occupied = Output(Bool()) // Indicates that the data unit is occupied to the vss
}

/** One hop of the task ring. Two builds, selected by `elastic`.
  *
  * RIGID (default) is an unconditional shift register. A hop can only be injected into on a cycle
  * when nothing happens to be passing through it, so an injector downstream of a saturated injector
  * never gets a turn: with a spawner flooding, the slot at the next injector is occupied every
  * cycle. Correct and cheap, and injection contention does not arise on the outside-spawn ring, so
  * that ring stays rigid.
  *
  * ELASTIC lets a hop refuse its upstream neighbour, which is how a hole gets made on demand rather
  * than waited for. A node that wants to inject asserts stopOut, its own content advances, and the
  * slot it vacates is its own to fill instead of being refilled from upstream on the same cycle.
  * Two entries, because stopOut is driven from registered/local state and the neighbour therefore
  * gets to send one more item before it takes effect -- the second entry is where that item lands.
  * stopOut never depends on stopIn, so closing the ring creates no combinational loop.
  *
  * Liveness does not come from the ring. A fully occupied ring is a good steady state, not a stuck
  * one: every PE has a task sitting in front of it the moment it is ready to take one. Sustained
  * congestion is relieved by the scheduler server detecting it and absorbing tasks into HBM.
  */
class SchedulerNetworkDataUnit(taskWidth: Int, elastic: Boolean = false) extends Module {
  val io = IO(new SchedulerNetworkDataUnitIO(taskWidth, elastic))

  if (!elastic) {
    val taskReg = RegInit(0.U(taskWidth.W))
    val validReg = RegInit(false.B)

    // A slot can be injected into exactly when it is empty. Drive ready from that fact alone, NOT
    // from qOutTask.valid: a ready that depends combinationally on valid violates the decoupled
    // contract and makes any client logic that reads ready unelaboratable, because the client's own
    // valid then closes a combinational cycle back through the network. Injection is still gated on
    // valid by the elsewhen below, so the handshake is unchanged.
    io.connSS.qOutTask.ready := ~io.validIn
    // Likewise, a task is available exactly when one is present in the slot. Driving valid from
    // ready would hide the task from any client that had not already committed to taking it, making
    // "is something arriving?" unobservable. Consumption is still gated on ready by the when below.
    io.connSS.availableTask.bits := io.taskIn
    io.connSS.availableTask.valid := io.validIn

    when(io.connSS.availableTask.ready && io.validIn) {
      validReg := false.B
      taskReg := 0.U
    }.elsewhen(io.connSS.qOutTask.valid && ~io.validIn) {
      validReg := true.B
      taskReg := io.connSS.qOutTask.bits
    }.elsewhen(io.validIn) {
      validReg := io.validIn
      taskReg := io.taskIn
    }.otherwise {
      validReg := false.B
      taskReg := 0.U
    }

    io.taskOut := taskReg
    io.validOut := validReg
    io.occupied := validReg
  } else {
    val slots = Reg(Vec(2, UInt(taskWidth.W)))
    val count = RegInit(0.U(2.W))
    val hasTask = count =/= 0.U

    // We present our own oldest entry, not the task in transit.
    //
    // This is the one semantic difference from the rigid hop, and it is required rather than
    // preferred. The rigid hop presents `taskIn` -- the upstream hop's content -- and lets the
    // consumer clear THIS hop's register as the task goes past. That is only sound because a rigid
    // ring always shifts, so the upstream copy is overwritten the very next cycle regardless. Stall
    // the ring and the upstream hop keeps re-presenting the same task, and a client that holds
    // availableTask.ready high across cycles (SchedulerClient does, while count < minLengthThresh)
    // takes it a second time. Consuming our own oldest entry removes the hazard by construction:
    // the task we hand over is the one we pop.
    io.connSS.availableTask.bits := slots(0)
    io.connSS.availableTask.valid := hasTask
    val consumedLocally = io.connSS.availableTask.ready && hasTask

    // The oldest entry leaves if the downstream hop takes it, or our own server does.
    //
    // A soft stop -- the downstream node merely wanting the slot -- is overridable, and that is the
    // whole of `force`. Without it a producer downstream of us that asserts its want continuously
    // pins our entry here forever: we never forward, so `base` never reaches 0, so our own
    // qOutTask.ready is never high and our producer is starved outright. A hard stop is never
    // overridable, so forcing can only ever fill a slot that was genuinely free -- no task can be
    // dropped by it.
    val downstreamBlocked =
      io.stopInFull.get || (io.stopInWant.get && !io.forceForward.get)
    val forwarded = hasTask && !downstreamBlocked && !consumedLocally
    val removed = forwarded || consumedLocally

    // Room for a local injection is judged AFTER this cycle's movement, exactly as the control ring
    // judged it: a hop that is emptying is available now, not a cycle later. Note this makes
    // injection and upstream traffic mutually exclusive -- validIn counts towards the same total --
    // so a task already on the ring is never overtaken by one being introduced. The injector gets
    // its turn from the hole stopOut makes, not by jumping the queue.
    val base = count - removed.asUInt
    val countAfterMovement = base + io.validIn.asUInt
    io.connSS.qOutTask.ready := countAfterMovement === 0.U
    val accepted = io.connSS.qOutTask.valid && io.connSS.qOutTask.ready

    // Registered/local state only -- never stopIn, and never validIn. That is what bounds the ring
    // path to a fixed depth: the chain stopOut(i+1) -> stopIn(i) -> forwarded(i) -> validOut(i) ->
    // validIn(i+1) -> qOutTask.ready(i+1) terminates in the producer, because nothing on it feeds
    // stopOut(i+1) back again. A 9-hop ring and a 129-hop ring have the same depth.
    io.stopOutFull.get := count === 2.U
    io.stopOutWant.get := io.injectWanted.get

    val nextCount = countAfterMovement + accepted.asUInt
    assert(nextCount <= 2.U, "elastic ring hop overrun")

    // Shift down on removal, then append whatever arrived (injection only ever lands in an empty
    // hop, per countAfterMovement === 0 above).
    when(accepted) {
      slots(0) := io.connSS.qOutTask.bits
    }.otherwise {
      slots(0) := Mux(base === 0.U && io.validIn, io.taskIn, Mux(removed, slots(1), slots(0)))
      when(base === 1.U && io.validIn) { slots(1) := io.taskIn }
    }

    count := nextCount

    io.taskOut := slots(0)
    io.validOut := forwarded
    io.occupied := hasTask
  }
}
