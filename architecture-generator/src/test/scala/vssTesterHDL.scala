
import chisel3._
import chisel3.util._
import stealSide._
import axi4._
import axi4.lite.RegisterBlock
import util.readyValidMem
import org.scalatest.flatspec.AnyFlatSpec
import chiseltest._

class vssTester(taskWidth: Int, addrWidth:Int) extends Module {
    val sn   = Module(new stealNetwork(taskWidth, 3))
    val vss0 = Module(new virtualStealServer(taskWidth, 0, 2, 0, 1, 64))        // This vss will keep take in tasks.
    val vss1 = Module(new virtualStealServer(taskWidth, 9999999, 2, 0, 1, 64))  // This vss will keep giving away tasks.
    val rvm0 = Module(new readyValidMem(taskWidth, addrWidth))
    val rvm1 = Module(new readyValidMem(taskWidth, addrWidth))

    val axiFullCfg = axi4.Config(
        wAddr = addrWidth,
        wData = taskWidth,
        lite = false
    )

    val axi_mgmt_0       = IO(axi4.Slave(vss0.regBlock.axiConfig))
    val axi_mgmt_1       = IO(axi4.Slave(vss1.regBlock.axiConfig))
    val axi_full_0       = IO(axi4.Master(axiFullCfg))
    val axi_full_1       = IO(axi4.Master(axiFullCfg))
    
    vss0.regBlock.saveRegisterMap("output", "mmr0.registers")
    vss1.regBlock.saveRegisterMap("output", "mmr1.registers")

    axi_mgmt_0.suggestName("0_S_AXI_MGMT")
    axi_mgmt_1.suggestName("1_S_AXI_MGMT")

    sn.io.connSS(0)       <> vss0.io.connNetwork

    sn.io.connSS(1)       <> vss1.io.connNetwork

    sn.io.connSS(2).ctrl.serveStealReq.valid := false.B
    sn.io.connSS(2).ctrl.stealReq.valid      := false.B
    sn.io.connSS(2).data.availableTask.ready := false.B
    sn.io.connSS(2).data.qOutTask.valid      := false.B
    sn.io.connSS(2).data.qOutTask.bits       := 0.U(taskWidth.W)

    val n_cycle_counter = RegInit(1000.U)
    when(n_cycle_counter === 0.U){
        n_cycle_counter := 1000.U
        sn.io.connSS(2).ctrl.stealReq.valid      := true.B
    }.otherwise{
        n_cycle_counter := n_cycle_counter - 1.U
    }


    rvm0.io.read.get.address  <> vss0.io.read_address
    rvm0.io.read.get.data     <> vss0.io.read_data
    rvm0.io.write.get.address <> vss0.io.write_address
    rvm0.io.write.get.data    <> vss0.io.write_data

    rvm1.io.read.get.address  <> vss1.io.read_address
    rvm1.io.read.get.data     <> vss1.io.read_data
    rvm1.io.write.get.address <> vss1.io.write_address
    rvm1.io.write.get.data    <> vss1.io.write_data

    axi_mgmt_0 <> vss0.io.axi_mgmt
    axi_mgmt_1 <> vss1.io.axi_mgmt

    axi_full_0 <> rvm0.axi
    axi_full_1 <> rvm1.axi
}


class vssTestHDL extends AnyFlatSpec with ChiselScalatestTester{
    it should "Produce testable vss module" in {
        new (chisel3.stage.ChiselStage).emitVerilog(
        // Params (peCount: Int, taskWidth: Int, queueMaxLength:Int, qRamReadLatency: Int, qRamWriteLatency: Int)
        new vssTester(64, 64),
        Array( 
            "--emission-options=disableMemRandomization,disableRegisterRandomization",
            "--target-dir=output/" 
        )
        )
    }
}

