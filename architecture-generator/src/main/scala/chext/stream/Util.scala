package chext.stream

import chisel3._
import chisel3.experimental.SourceInfo

import chext.{elasticnew => elastic}

import chext.amba.axi4

object checkAlignment {
  def apply(task: elastic.Interface[Task[_]], axiCfg: axi4.Config, moduleName: String) = {
    val mask = ((axiCfg.wData / 8) - 1).U(axiCfg.wAddr.W)

    when(task.fire && ((task.bits.address & mask) =/= 0.U)) {
      printf(
        f"stream.${moduleName}: Unaligned access detected: address = 0x%%x, length = 0x%%x, AXI bus width = ${axiCfg.wData}b\n",
        task.bits.address,
        task.bits.length
      )
    }
  }
}
