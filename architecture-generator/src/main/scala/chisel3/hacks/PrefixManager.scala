package chisel3.hacks

import chisel3.internal.Builder
import chisel3.internal.HasId

// Based on the experimental prefix
object PrefixManager {
  type Prefix = Builder.Prefix

  def current: Prefix = Builder.getPrefix
  def currentStr = current.reverse.mkString("_")

  def set(prefix: Builder.Prefix): Unit = Builder.setPrefix(prefix)
  def clear(): Unit = Builder.clearPrefix()

  def push(p: String) = Builder.pushPrefix(p)
  def push(p: HasId) = Builder.pushPrefix(p)
  def pop(): Unit = Builder.popPrefix()

  def withRelative[T](p: String)(f: => T): T = {
    push(p)
    val ret = f
    if (current.nonEmpty) pop()
    ret
  }

  def withRelative[T](p: HasId)(f: => T): T = {
    val pushed = push(p)
    val ret = f
    if (pushed) pop()
    ret
  }

  def withAbsolute[T](prefix: Builder.Prefix)(f: => T): T = {
    val oldPrefix = current
    set(prefix)
    val ret = f
    set(oldPrefix)
    ret
  }

  def withNone[T](f: => T): T = {
    val oldPrefix = current
    clear()
    val ret = f
    set(oldPrefix)
    ret
  }
}
