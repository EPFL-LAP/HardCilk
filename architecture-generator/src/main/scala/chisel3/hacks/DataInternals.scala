package chisel3.hacks

import chisel3.{Data, Aggregate}
import chisel3.experimental.BaseModule
import chisel3.internal.{
  ChildBinding,
  PortBinding,
  SecretPortBinding,
  WireBinding,
  AggregateViewBinding,
  HasId
}
import scala.annotation.nowarn

import scala.reflect.ClassTag

object DataInternals {
  @nowarn // deprecation warning
  def isIO(data: Data): Boolean = {
    data.binding match {
      case Some(value: ChildBinding)      => isIO(value.parent)
      case Some(value: PortBinding)       => true
      case Some(value: SecretPortBinding) => true
      case Some(value: AggregateViewBinding) => {
        // TODO figure out a way to make this function return the IO
        // binding of the original Data that is viewed.
        false
      }
      case _ => false
    }
  }

  @nowarn // deprecation warning
  def isWire(data: Data): Boolean = {
    data.binding match {
      case Some(value: ChildBinding) => isWire(value.parent)
      case Some(value: WireBinding)  => true
      case Some(value: AggregateViewBinding) => {
        // TODO figure out a way to make this function return the Wire
        // binding of the original Data that is viewed.
        false
      }
      case _ => false
    }
  }

  def getOwningModuleOption(data: Data): Option[BaseModule] = data._parent

  def getOwningModule(data: Data): BaseModule =
    getOwningModuleOption(data).get

  def getChildrenOfType[T <: Data: ClassTag](data: Data): Seq[T] = {
    data match {
      case t: T => Seq(t)
      case agg: Aggregate =>
        agg.getElements.map { x => getChildrenOfType[T](x) }.flatten
      case _ => Seq.empty
    }
  }

  def earlyName[T <: Data](data: Data): String = {
    data.earlyName
  }
}
