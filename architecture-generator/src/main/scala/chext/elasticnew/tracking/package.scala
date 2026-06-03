package chext.elasticnew

import chisel3.Data
import chisel3.experimental.SourceInfo

package object tracking {
  def register(): Unit = {}
  def uniquePrefix[T](fn: => T): T = fn
  def uniquePrefix[T](name: String)(fn: => T): T = fn
}
