package continuationSide

import chisel3._
import chisel3.util._
import chisel3.experimental.prefix

import stealSide._
import continuationSide._
import argRouting._
import commonInterfaces._

import chext.axi4
import chext.axis4

import axi4.Ops._
import axi4.lite.components.RegisterBlock

import axis4.Casts._

import hardcilk.util.readyValidMem


class continuationAllocationSideIO(
    val addrWidth: Int,
    val peCount: Int,
    val axiMgmtCfg: axi4.Config,
    val vcasAxiFullCfg: axi4.Config,
    val vcasCount : Int
) extends Bundle {
    implicit val axisCfgAddress: axis4.Config = axis4.Config(wData = addrWidth, onlyRV = true)
    val contOut    = Vec(peCount, axis4.Master(axisCfgAddress))
    val axi_mgmt_vcas = Vec(vcasCount, axi4.Slave(axiMgmtCfg))
    val vcas_axi_full = Vec(vcasCount, axi4.Master(vcasAxiFullCfg))
}

/***
 * 
 * @param addrWidth 
 *      The width of the system address
 * 
 * @param peCount
 *      The total number of PEs that issue a spawn_next of this task type
 * 
 * @param queueDepth
 *      The depth of the queue that holds the continuation addresses for the connected PEs
 * 
 * @param taskName 
 *      The name of the task that is being spawned next
 * 
 */

class continuationAllocationSide(
    val addrWidth: Int,
    val peCount: Int,
    val vcasCount: Int,
    val queueDepth: Int,
) extends Module {

    
    val continuationNetwork = Module(new contNetwork( addrWidth  = addrWidth,
                                           peCount    = peCount,
                                           queueDepth = queueDepth,
                                           vcasCount = vcasCount)
                                           )
    
    val vcas = Seq.fill(vcasCount)(Module(new virtContAddrServer( dataWidth = addrWidth,
                                               sysAddressWidth = addrWidth,
                                               burstLength = 15)
                                               ))

    val vcasAxiFullCfg = axi4.Config(
        wAddr = addrWidth,
        wData = addrWidth,
        lite = false
    )

    val io = IO(new continuationAllocationSideIO(addrWidth, peCount, vcas(0).regBlock.cfgAxi, vcasAxiFullCfg, vcasCount))
    
    val vcasRvmRO = Seq.fill(vcasCount)(Module(new readyValidMem(addrWidth, addrWidth, write=false, burstLength=15)))


    for(i <- 0 until vcasCount){
        io.axi_mgmt_vcas(i)               <> vcas(i).io.axi_mgmt
        
        vcasRvmRO(i).io.read.get.address  <> vcas(i).io.read_address
        vcasRvmRO(i).io.read.get.data     <> vcas(i).io.read_data
        vcasRvmRO(i).axi                  <> io.vcas_axi_full(i)
        vcas(i).io.dataOut                  <> continuationNetwork.io.connVCAS(i)
    }


    for(i <- 0 until peCount){
        continuationNetwork.io.connPE(i) <> io.contOut(i).lite
    }
}
/*
class wrapper() extends Module {
    val alloc = Module (new continuationAllocationSide(  
                                addrWidth = 64,
                                peCount = 8,
                                queueDepth = 2,
                        ))
    

    val vcasConn = IO(chiselTypeOf(alloc.io)).suggestName("fib")
    vcasConn <> alloc.io
}
*/