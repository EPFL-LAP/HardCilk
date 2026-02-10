// package Util

// import chisel3._
// import chisel3.util.log2Ceil

// import chext.elastic
// import elastic.ConnectOp._

// import chext.amba.axi4
// import chisel3.experimental.AffectsChiselPrefix
// import chisel3.experimental.prefix


// /** Converts narrow transfers to full-sized transfers.
//   *
//   * @note
//   *   Works only for INCR Burst
//   *   Works only when AxSize is the same as the Bus Width
//   */
// class Split4kBurst(val cfg: axi4.Config) extends Module {
//   require(!cfg.lite, "Split4kBurst requires an AXI4-Full interface.")

//   val s_axi = IO(axi4.full.Slave(cfg))
//   val m_axi = IO(axi4.full.Master(cfg))

//     /** Transforms the address packet to match the target AxSIZE.
//     */
//   private def transformAx(
//       source: elastic.Interface[axi4.full.AddressChannel],
//       sink: chisel3.util.IrrevocableIO[axi4.full.AddressChannel],
//       size: Int
//   ): AffectsChiselPrefix = {
//     // calculates the size of the master-side AXI transaction
//     // Please check Transaction.hpp, simpleRead to understand the logic
//     new elastic.Transform(source, sink) {
//       protected def onTransform: Unit = {
//         out := in

//         val mask0 = WireInit((1.U << in.size) - 1.U)
//         val mask1 = WireInit(((1 << size) - 1).U)

//         // .pad is necessary, otherwise the negated signal is extended with zeros,
//         // corrupting the address
//         // HARDCODED: addr(7, 0) and .pad(8)
//         val addr0 = WireInit(in.addr(7, 0) & ~(mask0.pad(8)))
//         val addr1 = WireInit(in.addr(7, 0) & ~(mask1.pad(8)))

//         // aligned transfer size
//         // pad(9) and pad(17) to avoid overflows/underflows
//         val dtsize =
//           ((in.len.pad(9) + 1.U) << in.size).pad(17) +% addr0 -% addr1

//         val len0 = WireInit(dtsize >> size.U)
//         val len1 = WireInit(Mux((dtsize & mask1) > 0.U, len0 +% 1.U, len0))

//         out.size := size.U
//         out.len := len1 -% 1.U

//         // left here for debugging purposes
//         dontTouch(mask0)
//         dontTouch(mask1)
//         dontTouch(addr0)
//         dontTouch(addr1)
//         dontTouch(dtsize)
//         dontTouch(len0)
//         dontTouch(len1)
//       }
//     }
//   }

// }