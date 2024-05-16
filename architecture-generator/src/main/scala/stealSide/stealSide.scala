package stealSide

import chisel3._
import chisel3.util._
import chisel3.experimental.prefix
import scala.math._
import stealSide._
import continuationSide._
import argRouting._
import commonInterfaces._

import hardcilk.util.readyValidMem

import chext.axi4
import chext.axis4

import axi4.Ops._
import axi4.lite.components.RegisterBlock

import axis4.Casts._

//import util.readyValidMemVSS

class stealSideIO(
    val addrWidth: Int,
    val taskWidth: Int,
    val peCount: Int,
    val vssCount: Int,
    val spawnsItself: Boolean,
    val peCountGlobalTaskIn: Int,
    val axiMgmtCfg: axi4.Config
    
    ) extends Bundle{

    implicit val axisCfgTask: axis4.Config    = axis4.Config(wData = taskWidth, onlyRV = true)
    val vssAxiFullCfg = axi4.Config(
            wAddr = addrWidth,
            wData = taskWidth,
            lite = false
        )

    val axi_mgmt_vss = Vec(vssCount,axi4.Slave(axiMgmtCfg))
    val vss_axi_full = Vec(vssCount,axi4.Master(vssAxiFullCfg))

    val taskOut = Vec(peCount, axis4.Master(axisCfgTask))    

    val taskIn = if (spawnsItself) Some(Vec(peCount, axis4.Slave(axisCfgTask))) else None
    val taskInGlobal = if (peCountGlobalTaskIn > 0) Some(Vec(peCountGlobalTaskIn, axis4.Slave(axisCfgTask))) else None
}

class stealSide(
    val addrWidth: Int,
    val taskWidth: Int,
    val queueDepth: Int,
    val peCount: Int,
    val virtualAddressServersNumber: Int,
    val spawnsItself: Boolean,
    val peCountGlobalTaskIn: Int,
    val argRouteServersNumber: Int,
) extends Module{
    
    val successiveNetworkConfig = if ((peCountGlobalTaskIn + argRouteServersNumber) > 0) true else false

    val stealNW_TQ = Module(new stealNW_TQ( peCount = peCount,
                                vssCount = virtualAddressServersNumber,
                                vasCount = argRouteServersNumber + peCountGlobalTaskIn,
                                taskWidth = taskWidth, 
                                queueMaxLength = queueDepth, 
                                qRamReadLatency = 1, 
                                qRamWriteLatency = 2,
                                spawnsItself = spawnsItself,
                                successiveNetworkConfig = successiveNetworkConfig)
                                )

    val virtualStealServers = Seq.fill(virtualAddressServersNumber)(Module(new virtualStealServer( taskWidth = taskWidth, 
                                              contentionThreshold = (max((peCount + argRouteServersNumber + peCountGlobalTaskIn)/1.2, 1)).toInt,
                                              peCount = peCount,
                                              contentionDelta = 0,
                                              vasCount = argRouteServersNumber + peCountGlobalTaskIn,
                                              sysAddressWidth = addrWidth,
                                              ignoreRequestSignals = successiveNetworkConfig)
                                              ))
    
    val io = IO(new stealSideIO( addrWidth = addrWidth,
                                 taskWidth = taskWidth,
                                 peCount = peCount,
                                 vssCount = virtualAddressServersNumber,
                                 spawnsItself = spawnsItself,
                                 peCountGlobalTaskIn = peCountGlobalTaskIn,
                                 axiMgmtCfg = virtualStealServers(0).regBlock.cfgAxi)
    )
    
    val connSyncSide = IO(Vec(argRouteServersNumber, new stNwStSrvConn(taskWidth)))

    for(i <- 0 until virtualAddressServersNumber){
        io.axi_mgmt_vss(i) <> virtualStealServers(i).io.axi_mgmt
        virtualStealServers(i).io.ntwDataUnitOccupancy <> stealNW_TQ.io.ntwDataUnitOccupancyVSS(i)
    }
    
    val vssRvm       = Seq.fill(virtualAddressServersNumber)(Module(new readyValidMem(taskWidth, addrWidth, varBurst = true)))

    
    for(i <- 0 until virtualAddressServersNumber){
        vssRvm(i).io.read.get.address         <> virtualStealServers(i).io.read_address
        vssRvm(i).io.read.get.data            <> virtualStealServers(i).io.read_data
        vssRvm(i).io.write.get.address        <> virtualStealServers(i).io.write_address
        vssRvm(i).io.write.get.data           <> virtualStealServers(i).io.write_data
        vssRvm(i).io.readBurst.get.len        := virtualStealServers(i).io.read_burst_len
        vssRvm(i).io.writeBurst.get.len       := virtualStealServers(i).io.write_burst_len
        vssRvm(i).io.writeBurst.get.last      := virtualStealServers(i).io.write_last
        vssRvm(i).axi                         <> io.vss_axi_full(i)
        virtualStealServers(i).io.connNetwork <> stealNW_TQ.io.connVSS(i)
    }

    for(i <- 0 until peCount){
        stealNW_TQ.io.connPE(i).pop   <> io.taskOut(i).lite
        if(spawnsItself){
            stealNW_TQ.io.connPE(i).push  <> io.taskIn.get(i).lite
        }else{
            stealNW_TQ.io.connPE(i).push.valid := false.B
            stealNW_TQ.io.connPE(i).push.bits  := DontCare
        }
    }

    if(argRouteServersNumber > 0){
        for(i <- 0 until argRouteServersNumber){
            stealNW_TQ.io.connVAS(i) <> connSyncSide(i)
        }
    }

    if(peCountGlobalTaskIn > 0){
        val globalsTaskBuffers = Seq.fill(peCountGlobalTaskIn)(Module(new globalTaskBuffer(taskWidth, peCount)))
        for(i <- argRouteServersNumber until (argRouteServersNumber + peCountGlobalTaskIn)){
            stealNW_TQ.io.connVAS(i) <> globalsTaskBuffers(i-argRouteServersNumber).io.connStealNtw
            globalsTaskBuffers(i-argRouteServersNumber).io.in <> io.taskInGlobal.get(i-argRouteServersNumber).lite
        }
    }
    
}


// class wrapper() extends Module {
//     val alloc = Module (new stealSide( addrWidth = 64,
//                                        taskWidth = 256,
//                                        queueDepth = 2,
//                                        peCount = 8,
//                                        spawnsItself = true,
//                                        peCountGlobalTaskIn = 10,
//                                        argRouteServersNumber = 2,
//                                        virtualAddressServersNumber = 1,
//                                        )
//                                     )
    

//     val syncConn = IO(chiselTypeOf(alloc.io)).suggestName("fib")
//     syncConn <> alloc.io

//     val internalConn = IO(chiselTypeOf(alloc.connSyncSide)).suggestName("fib_internal")
//     internalConn <> alloc.connSyncSide
// }
