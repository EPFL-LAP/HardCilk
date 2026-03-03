package chext.elasticnew.tracking

import chisel3.Data
import chisel3.util.ReadyValidIO
import chisel3.experimental.SourceInfo

sealed abstract case class DeclaredRole(str: String)

object DeclaredRole {
  object Source extends DeclaredRole("Source")
  object Sink extends DeclaredRole("Sink")
  object None extends DeclaredRole("None")
}

/** Stub trait for HasPath - provides minimal interface */
trait HasPath {
  def sourceInfo: SourceInfo
  def tpe: String
  def namePrefix: String
}

/** Stub trait for Container */
trait Container extends HasPath {
  def addChild(baseComponent: BaseComponent): Unit = {}
}

/** Stub trait for BaseComponent */
trait BaseComponent extends HasPath

/** Stub trait for Component */
trait Component extends BaseComponent {
  protected def addSourcePort[T <: Data](name: String, source: ReadyValidIO[T])(implicit sourceInfo: SourceInfo): Unit = {}

  protected def addSinkPort[T <: Data](name: String, sink: ReadyValidIO[T])(implicit sourceInfo: SourceInfo): Unit = {}
}

/** Stub for RigidComponent */
final class RigidComponent(
    override val tpe: String,
    override val namePrefix: String = ""
)(implicit val sourceInfo: SourceInfo) extends Component {
  def source[T <: Data](name: String, source: ReadyValidIO[T]): Unit = {
    addSourcePort(name, source)(sourceInfo)
  }

  def sink[T <: Data](name: String, sink: ReadyValidIO[T]): Unit = {
    addSinkPort(name, sink)(sourceInfo)
  }
}

/** Stub for withContainer */
object withContainer {
  def apply[T](container: Container)(fn: => T): T = fn
}
