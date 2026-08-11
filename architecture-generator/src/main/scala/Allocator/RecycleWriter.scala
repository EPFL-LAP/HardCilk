package Allocator

import chisel3._
import chisel3.util._

/** Writes freed continuation addresses back into one AllocatorServer's circular
  * FIFO, through the write half of that server's AXI port.
  *
  * Beats of packed addresses arrive off the recycle ring. The writer accumulates
  * them into full bursts and lands them at its own tail pointer, then tells the
  * server how many addresses became available -- on the B response, never on
  * WLAST, so the read side can never hand out an address whose write might still
  * be in flight.
  *
  * The absorb rule is the load-bearing one: a beat is taken off the ring ONLY
  * when a region slot is already reserved for it. A writer that accepted beats
  * it could not place would be a dead end the ring cannot route around, and the
  * region could overflow. Refusing instead leaves the beat circulating to a
  * writer that does have room -- and since total region capacity equals the
  * total number of addresses in existence, a circulating beat is itself proof
  * that some region has space for it.
  */
class RecycleWriter(
    dataWidth: Int,
    sysAddressWidth: Int,
    burstLength: Int,
    maxOutstandingWriteBursts: Int = 8
) extends Module {

  require(burstLength >= 0 && burstLength <= 15)

  private val addressAlignmentBits = log2Ceil(dataWidth / 8)
  private val beatCountWidth = sysAddressWidth - addressAlignmentBits + 1
  private val burstBeats = burstLength + 1
  // Deep enough to accumulate one burst while the previous one streams out.
  private val beatQueueDepth = 4 * burstBeats
  private val writeCountWidth = log2Ceil(maxOutstandingWriteBursts + 1) + 1
  private val reservedWidth =
    log2Ceil(beatQueueDepth + maxOutstandingWriteBursts * burstBeats + 1) + 1

  val io = IO(new Bundle {
    val beatIn = Flipped(Decoupled(UInt(dataWidth.W)))
    val server = Flipped(new AllocatorRecyclePort(sysAddressWidth, beatCountWidth))
    val write_address = Decoupled(UInt(sysAddressWidth.W))
    val write_data = Decoupled(UInt(dataWidth.W))
    val write_last = Output(Bool())
    // One pulse per completed write burst (B response), straight from the AXI
    // adapter.
    val write_done = Input(Bool())
  })

  private val beatQueue = Module(new Queue(UInt(dataWidth.W), beatQueueDepth))
  private val tailBeats = RegInit(0.U(beatCountWidth.W))
  private val outstandingWrites = RegInit(0.U(writeCountWidth.W))
  private val streaming = RegInit(false.B)
  private val beatsLeft = RegInit(0.U(log2Ceil(burstBeats + 1).W))
  // Beats absorbed off the ring whose slots are claimed but not yet handed back
  // to the server: still in the queue, or in an unacknowledged burst.
  private val reservedBeats = RegInit(0.U(reservedWidth.W))

  private val canAbsorb =
    io.server.running && (reservedBeats +& 1.U) <= io.server.writableBeats

  beatQueue.io.enq.valid := io.beatIn.valid && canAbsorb
  beatQueue.io.enq.bits := io.beatIn.bits
  io.beatIn.ready := beatQueue.io.enq.ready && canAbsorb

  // A whole burst is accumulated before AW goes out, so the W beats behind it
  // are always available and the data phase never stalls mid-burst.
  private val burstReady = beatQueue.io.count >= burstBeats.U
  io.write_address.valid :=
    io.server.running && !streaming && burstReady &&
      outstandingWrites < maxOutstandingWriteBursts.U
  io.write_address.bits :=
    (io.server.baseAddress + (tailBeats << addressAlignmentBits))(
      sysAddressWidth - 1,
      0
    )

  io.write_data.valid := streaming && beatQueue.io.deq.valid
  io.write_data.bits := beatQueue.io.deq.bits
  beatQueue.io.deq.ready := streaming && io.write_data.ready
  io.write_last := beatsLeft === 1.U

  when(io.write_address.fire) {
    val next = tailBeats +& burstBeats.U
    tailBeats := Mux(
      next >= io.server.capacityBeats,
      next - io.server.capacityBeats,
      next
    )
    streaming := true.B
    beatsLeft := burstBeats.U
  }
  when(streaming && io.write_data.fire) {
    beatsLeft := beatsLeft - 1.U
    when(beatsLeft === 1.U) { streaming := false.B }
  }

  outstandingWrites := outstandingWrites +
    Mux(io.write_address.fire, 1.U, 0.U) - Mux(io.write_done, 1.U, 0.U)

  reservedBeats := (reservedBeats +& beatQueue.io.enq.fire.asUInt) -
    Mux(io.write_done, burstBeats.U, 0.U)

  // The server only learns about the recycled addresses here, one burst per B.
  io.server.commit := io.write_done
}
