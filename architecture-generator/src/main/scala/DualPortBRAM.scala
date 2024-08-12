// This implementation is a Chisel3 implementation of the one found
// in https://github.com/maltanar/fpga-tidbits

// Write Latency 2 cycles. 
// Read  Latency 1 cycle.

package dualPortBRAM

import  chisel3._
import chisel3.util._
import commonInterfaces._

class BRAMPort(addrBits: Int, dataBits: Int) extends  Bundle {
    val addr = Input(UInt(addrBits.W))
    val din  = Input(UInt(dataBits.W))
    val wr   = Input(Bool())
    val dout = Output(UInt(dataBits.W))
}

class DualPortBRAMIO(addrBits: Int, dataBits: Int) extends Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())

    val a   = new BRAMPort(addrBits, dataBits)
    val b   = new BRAMPort(addrBits, dataBits)
}

class DualPortBRAM(addrBits: Int, dataBits: Int) extends BlackBox (Map( "DATA" -> dataBits, // Verilog params
                                                                       "ADDR" -> addrBits)) 
                                                 with HasBlackBoxResource
{
  val io = IO(new DualPortBRAMIO(addrBits, dataBits))

  /** @todo Get the generation type as a constructor parameter, not from the environment. */
  private val generationType = sys.env.getOrElse("HARDCILK_GENERATION_TYPE", "Simulation")

  assert(generationType == "Simulation" || generationType == "Synthesis")

  // if (generationType == "Simulation")
  //   addResource("DualPortBRAM_sim.v")
  // else
  //   addResource("DualPortBRAM_xpm.v")
}
