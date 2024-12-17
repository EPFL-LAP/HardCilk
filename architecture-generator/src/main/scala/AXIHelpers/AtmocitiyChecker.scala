// /**
//  * Atmocity checker is a module that has input axi and output axi with the same configuration.
//  * This checker acts as a write pass-through and only operates on read transactions.
//  * If ArLock of a read transaction is asserted, 
//  *    the checker will check the AwUser bits 1 means lock and 0 means unlock.
//  * The module has two AXI stream interfaces, one where it can request a lock/unlock and another to receive the result of the lock request.
//  * The module will request a lock command on the axi stream port by sending the address of the lock/unlock bit and the value to be written.
//  * The module will receive the result of the lock request on the axi stream port by receiving the address of the lock/unlock bit and the value that was read.
//  * If lock was successful, a reply on the R channel will have the value zero. otherwise, it will have the value 1.
// */

// package AXIHelpers

// import chisel3._
// import chisel3.util._

// import chext.amba.axi4s
// import chext.elastic._
// import chext.elastic.ConnectOp._

// import chext.amba.axi4._
// import chext.amba.axi4.Ops._

// class AtomicityChecker(
//     cfg: Config
//     addrWidth: Int = 64
// ) extends Module {
//     val s_axi = IO(full.Slave(cfg))
//     val m_axi = IO(full.Master(cfg))

//     val m_axis_config = axi4s.Config(wData = 1 + addrWidth, onlyRV = true)
//     val s_axis_config = axi4s.Config(wData = 1, onlyRV = true)

//     val m_axis = IO(axi4s.Master(m_axis_config))
//     val s_axis = IO(axi4s.Slave(s_axis_config))

//     // TODO

// }

