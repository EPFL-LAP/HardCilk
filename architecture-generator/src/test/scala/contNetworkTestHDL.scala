
import chisel3._
import chisel3.util._
import continuationSide._
import org.scalatest.flatspec.AnyFlatSpec
import chiseltest._
import util.readyValidMem


class contNetworkTesterHDL(addrWidth: Int, peNum : Int) extends Module {
    val axiFullCfg = axi4.Config(
            wAddr = 64,
            wData = addrWidth,
            lite = false
        )

    val vcas0   = Module(new virtContAddrServer(addrWidth, 64))
    val cn      = Module(new contNetwork(addrWidth, peNum, 3))
    val rvmro0  = Module(new readyValidMem(addrWidth, 64, write=false))

    val axi_full_0 = IO(axi4.Master(axiFullCfg))
    val axi_mgmt_0 = IO(axi4.Slave(vcas0.regBlock.axiConfig))
    val connPE     = IO(Vec(peNum, DecoupledIO(UInt(addrWidth.W))))

    axi_mgmt_0.suggestName("0_S_AXI_MGMT")
    //axi_full_0.suggestName("0_M_AXI_FULL")

    axi_mgmt_0              <> vcas0.io.axi_mgmt
    rvmro0.io.read.get.address  <> vcas0.io.read_address
    rvmro0.io.read.get.data     <> vcas0.io.read_data
    rvmro0.axi              <> axi_full_0
    

    cn.io.connVCAS          <> vcas0.io.dataOut 
    connPE                  <> cn.io.connPE
        
    vcas0.regBlock.saveRegisterMap("output", "vcas0.registers")
}


class contNetworkTestHDL extends AnyFlatSpec with ChiselScalatestTester{
    it should "Produce testable continuation network and vcas hdl module" in {
        new (chisel3.stage.ChiselStage).emitVerilog(
        // Params (peCount: Int, taskWidth: Int, queueMaxLength:Int, qRamReadLatency: Int, qRamWriteLatency: Int)
        new contNetworkTesterHDL(64, 2),
        Array( 
            "--emission-options=disableMemRandomization,disableRegisterRandomization",
            "--target-dir=output/" 
        )
        )
    }
}

