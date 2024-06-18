package stealSide

import chisel3._
import chisel3.util._
import chisel3.experimental.prefix
import scala.math._
import continuationSide._
import argRouting._
import commonInterfaces._

import hardcilk.util.readyValidMem

import chext.axi4
import chext.axis4

import axi4.Ops._
import axi4.lite.components.RegisterBlock
import chext.axi4.full.components._

import axis4.Casts._
import AXIHelpers.axisDwConverter

class stealSideIO_export(
    val pePortWidth: Int,
    val peCount: Int,
    val spawnsItself: Boolean,
    val peCountGlobalTaskIn: Int,
    ) extends Bundle{

    implicit val axisCfgTask: axis4.Config    = axis4.Config(wData = pePortWidth, onlyRV = true)


    val taskOut = Vec(peCount, axis4.Master(axisCfgTask))    

    val taskIn = if (spawnsItself) Some(Vec(peCount, axis4.Slave(axisCfgTask))) else None
    val taskInGlobal = if (peCountGlobalTaskIn > 0) Some(Vec(peCountGlobalTaskIn, axis4.Slave(axisCfgTask))) else None
}

class stealSideIO_internal(
    val vssCount: Int,
    val axiMgmtCfg: axi4.Config,    
    val addrWidth: Int,
    val taskWidth: Int,
    val vssAxiFullCfg: axi4.Config
    ) extends Bundle{


    val vss_axi_full = axi4.full.Master(vssAxiFullCfg)
    val axi_mgmt_vss = Vec(vssCount,axi4.lite.Slave(axiMgmtCfg))
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
    val pePortWidth: Int
) extends Module{
    
    val successiveNetworkConfig = if ((peCountGlobalTaskIn + argRouteServersNumber) > 0) true else false


    val vssAxiFullCfg = axi4.Config(
        wAddr = addrWidth,
        wData = taskWidth,
        lite = false
    )

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
    
    val io_export = IO(new stealSideIO_export(
                                 pePortWidth = pePortWidth,
                                 peCount = peCount,
                                 spawnsItself = spawnsItself,
                                 peCountGlobalTaskIn = peCountGlobalTaskIn
                                 )
    )
    
    val io_internal = IO(new stealSideIO_internal( addrWidth = addrWidth,
                                 taskWidth = taskWidth,
                                 vssCount = virtualAddressServersNumber,
                                 axiMgmtCfg = virtualStealServers(0).regBlock.cfgAxi,
                                 vssAxiFullCfg = vssAxiFullCfg
                                 )
    )

    val mux = Module(new Mux(axiCfgSlave = vssAxiFullCfg, numSlaves=virtualAddressServersNumber, muxCfg = MuxConfig(slaveBuffers = axi4.BufferConfig.all(1))))
    mux.m_axi :=> io_internal.vss_axi_full 

    val connSyncSide = IO(Vec(argRouteServersNumber, new stNwStSrvConn(taskWidth)))

    for(i <- 0 until virtualAddressServersNumber){
        io_internal.axi_mgmt_vss(i) :=> virtualStealServers(i).io.axi_mgmt
        virtualStealServers(i).io.ntwDataUnitOccupancy <> stealNW_TQ.io.ntwDataUnitOccupancyVSS(i)
    }
    
    val vssRvm       = Seq.fill(virtualAddressServersNumber)(Module(new readyValidMem(taskWidth, addrWidth, varBurst = true)))

    
    for(i <- 0 until virtualAddressServersNumber){
        vssRvm(i).io.read.get.address         <>  virtualStealServers(i).io.read_address
        vssRvm(i).io.read.get.data            <>  virtualStealServers(i).io.read_data
        vssRvm(i).io.write.get.address        <>  virtualStealServers(i).io.write_address
        vssRvm(i).io.write.get.data           <>  virtualStealServers(i).io.write_data
        vssRvm(i).io.readBurst.get.len        :=  virtualStealServers(i).io.read_burst_len
        vssRvm(i).io.writeBurst.get.len       :=  virtualStealServers(i).io.write_burst_len
        vssRvm(i).io.writeBurst.get.last      :=  virtualStealServers(i).io.write_last
        vssRvm(i).axi                         :=> mux.s_axi(i)  
        virtualStealServers(i).io.connNetwork <>  stealNW_TQ.io.connVSS(i)
    }

    // If taskWidth == pePortWidth, the converter is created as just a wire.
    val axis_stream_converters_out = Seq.fill(peCount)(Module(new axisDwConverter(taskWidth, pePortWidth)))
    val axis_stream_converters_in = if(spawnsItself) Some(Seq.fill(peCount)(Module(new axisDwConverter(pePortWidth, taskWidth))) ) else None
    for(i <- 0 until peCount){
        axis_stream_converters_out(i).io.dataIn.lite <> stealNW_TQ.io.connPE(i).pop
        io_export.taskOut(i).lite <> axis_stream_converters_out(i).io.dataOut.lite
        if(spawnsItself){
            axis_stream_converters_in.get(i).io.dataIn.lite <> io_export.taskIn.get(i).lite
            stealNW_TQ.io.connPE(i).push <> axis_stream_converters_in.get(i).io.dataOut.lite
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
        val axis_stream_converters_in_global = Seq.fill(peCountGlobalTaskIn)(Module(new axisDwConverter(pePortWidth, taskWidth)))
        val globalsTaskBuffers = Seq.fill(peCountGlobalTaskIn)(Module(new globalTaskBuffer(taskWidth, peCount)))
        for(i <- argRouteServersNumber until (argRouteServersNumber + peCountGlobalTaskIn)){
            axis_stream_converters_in_global(i-argRouteServersNumber).io.dataIn.lite <> io_export.taskInGlobal.get(i-argRouteServersNumber).lite
            globalsTaskBuffers(i-argRouteServersNumber).io.in <> axis_stream_converters_in_global(i-argRouteServersNumber).io.dataOut.lite
            stealNW_TQ.io.connVAS(i) <> globalsTaskBuffers(i-argRouteServersNumber).io.connStealNtw
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
    

//     val syncConn0 = IO(chiselTypeOf(alloc.io_export)).suggestName("fib")
//     syncConn0 <> alloc.io_export

//     val syncConn1 = IO(chiselTypeOf(alloc.io_internal)).suggestName("fib_1")
//     // connect each axi port
//     for(i <- 0 until alloc.io_internal.vssCount){
//         syncConn1.axi_mgmt_vss(i) :=> alloc.io_internal.axi_mgmt_vss(i)
//     }

//     val syncConn2 = IO(chiselTypeOf(alloc.io_axi)).suggestName("fib_2")
//     // connect each axi port
//     for(i <- 0 until alloc.io_axi.vssCount){
//         alloc.io_axi.vss_axi_full(i) :=> syncConn2.vss_axi_full(i)
//     }

//     val internalConn = IO(chiselTypeOf(alloc.connSyncSide)).suggestName("fib_internal")
//     internalConn <> alloc.connSyncSide
// }

// object stealSide extends App {
//     (new chisel3.stage.ChiselStage).emitVerilog(
//         {
//           val module = (new wrapper())
//           module
//         },
//         Array(
//           "--emission-options=disableMemRandomization,disableRegisterRandomization",
//           f"--target-dir=output"
//         )
//       )
// }