package argRouting



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

class syncSideIO(
    val addrWidth: Int,
    val peCount: Int,
    val argRouteServersNumber: Int,
    val contCounterWidth: Int,
    
    ) extends Bundle{
    
    implicit val axisCfgAddress: axis4.Config = axis4.Config(wData = addrWidth, onlyRV = true)
    
    val argRouteAxiFullCfg = axi4.Config(wAddr = addrWidth, wData = contCounterWidth, lite = false)
    val addrIn = Vec(peCount, axis4.Slave(axisCfgAddress))
    val axi_full_argRoute = Vec(2*argRouteServersNumber, axi4.Master(argRouteAxiFullCfg))
}


class syncSide(
    val addrWidth: Int,
    val taskWidth: Int,
    val queueDepth: Int,
    val peCount: Int,
    val argRouteServersNumber: Int,
    val contCounterWidth: Int,
) extends Module {

    val io = IO(new syncSideIO(addrWidth, peCount, argRouteServersNumber, contCounterWidth))
    val connStealNtw = IO(Vec(argRouteServersNumber, Flipped(new stNwStSrvConn(taskWidth))))
        

    val argSide = Module(new argRouteNetwork( addrWidth = addrWidth,
                                              taskWidth = taskWidth, 
                                              peCount = peCount,
                                              vasNum = argRouteServersNumber,
                                              queueDepth = queueDepth)
                                         )

    val serversList       = List.tabulate(argRouteServersNumber)(n => n)
    val argRouteServers = serversList.zipWithIndex.map { case (tag, index) =>
        Module(new argRouteVirtServerV2( 
                                    taskWidth = taskWidth,
                                    counterWidth = contCounterWidth,
                                    sysAddressWidth = addrWidth, 
                                    tagBitsShift = log2Ceil(taskWidth/8),
                                    noContinuations = false
                                    ))
        }

    
    val argRouteRvm       = Seq.fill(argRouteServersNumber)(Module(new readyValidMem(contCounterWidth, addrWidth)))
    val argRouteRvmReadOnly = Seq.fill(argRouteServersNumber)(Module(new readyValidMem(contCounterWidth, addrWidth, write = false, burstLength = (taskWidth/contCounterWidth)-2)))
    
    for(i <- 0 until argRouteServersNumber){
        io.axi_full_argRoute(i)             <> argRouteRvm(i).axi
        argRouteRvm(i).io.read.get.address  <> argRouteServers(i).io.read_address
        argRouteRvm(i).io.read.get.data     <> argRouteServers(i).io.read_data
        argRouteRvm(i).io.write.get.address <> argRouteServers(i).io.write_address
        argRouteRvm(i).io.write.get.data    <> argRouteServers(i).io.write_data
        argRouteServers(i).io.connNetwork   <> argSide.io.connVAS(i)
        argRouteServers(i).io.connStealNtw  <> connStealNtw(i)

        io.axi_full_argRoute(i+argRouteServersNumber) <> argRouteRvmReadOnly(i).axi
        argRouteRvmReadOnly(i).io.read.get.address  <> argRouteServers(i).io.read_address_task
        argRouteRvmReadOnly(i).io.read.get.data     <> argRouteServers(i).io.read_data_task
    }       

    for(i <- 0 until peCount){
        argSide.io.connPE(i) <> io.addrIn(i).lite
    }
}

/*
class wrapper() extends Module {
    val alloc = Module (new syncSide( addrWidth = 64,
                                      taskWidth = 256,
                                      queueDepth = 2,
                                      peCount = 8,
                                      argRouteServersNumber = 2,
                                      contCounterWidth = 32
                                    )
                        )
    

    val syncConn = IO(chiselTypeOf(alloc.io)).suggestName("fib")
    syncConn <> alloc.io

    val internalConn = IO(chiselTypeOf(alloc.connStealNtw)).suggestName("fib_internal")
    internalConn <> alloc.connStealNtw
}
*/

