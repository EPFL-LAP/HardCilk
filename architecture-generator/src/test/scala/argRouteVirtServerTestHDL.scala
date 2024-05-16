import chisel3._
import chisel3.util._
import argRouting._
import org.scalatest.flatspec.AnyFlatSpec
import chiseltest._
import util.readyValidMem


class argVirtSrvrTesterHDL(addrWidth: Int, taskWidth: Int) extends Module {
    val axiFullCfg = axi4.Config(
            wAddr = 64,
            wData = addrWidth,
            lite = false
        )

    val tagBitsShift  = log2Ceil(taskWidth/8)
    val vas0    = Module(new argRouteVirtServer(addressWidth = addrWidth, sysAddressWidth = addrWidth, tagBitsShift = tagBitsShift, regBlockBaseAddr = 0x0))
    val rvmro0  = Module(new readyValidMem(addrWidth, 64))
    
    
    val connNetwork = IO(Flipped(DecoupledIO(UInt(addrWidth.W))))
    val axi_full_0 = IO(axi4.Master(axiFullCfg))
    val axi_mgmt_0 = IO(axi4.Slave(vas0.regBlock.axiConfig))

    axi_mgmt_0.suggestName("S_AXI_MGMT")
    axi_full_0.suggestName("M_AXI_FULL")

    axi_mgmt_0                  <> vas0.io.axi_mgmt
    rvmro0.io.read.get.address  <> vas0.io.read_address
    rvmro0.io.read.get.data     <> vas0.io.read_data
    rvmro0.io.write.get.address <> vas0.io.write_address
    rvmro0.io.write.get.data    <> vas0.io.write_data
    rvmro0.axi                  <> axi_full_0
    

    connNetwork                 <> vas0.io.connNetwork 
        
    vas0.regBlock.saveRegisterMap("output", "vas0.registers")
}


class argVirtSrvrTestHDL extends AnyFlatSpec with ChiselScalatestTester{
    it should "Produce testable continuation network and vcas hdl module" in {
        new (chisel3.stage.ChiselStage).emitVerilog(
        // Params (peCount: Int, taskWidth: Int, queueMaxLength:Int, qRamReadLatency: Int, qRamWriteLatency: Int)
        new argVirtSrvrTesterHDL(64, 256),
        Array( 
            "--emission-options=disableMemRandomization,disableRegisterRandomization",
            "--target-dir=output/" 
        )
        )
    }
}
