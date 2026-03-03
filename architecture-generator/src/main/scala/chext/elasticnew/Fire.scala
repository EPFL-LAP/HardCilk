package chext.elasticnew

import chisel3._
import chisel3.util.ReadyValidIO

import chisel3.hacks.deferred

import scala.collection.mutable.ArrayBuffer

/** Provides the `fire { ... }` method that is executed when a packet is transferred at the sink
  * interface.
  */
private[elasticnew] trait Fire[Tout <: Data] {
  private var fireFns_ = ArrayBuffer.empty[() => Unit]

  protected def fireSink: ReadyValidIO[Tout]

  /** Sets an action that is executed when the sink interface fires. Can be called multiple times.
    */
  protected final def fire(fn: => Unit): Unit = {
    fireFns_.addOne(() => fn)
  }

  deferred {
    when(fireSink.fire) {
      fireFns_.foreach { _() }
    }
  }
}
