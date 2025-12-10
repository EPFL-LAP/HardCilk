package chisel3.hacks

import chisel3.Module
import chisel3.RawModule
import chisel3.ChiselException

import scala.collection.mutable.HashMap
import scala.collection.mutable.PriorityQueue

object deferred {
  // lower the priority hooks are executed later, default priority is one
  private case class Hook(priority: Int, fn: () => Unit)
  private implicit val hookOrdering: Ordering[Hook] = Ordering.by((h: Hook) => h.priority)

  private val hooks = HashMap.empty[RawModule, PriorityQueue[Hook]]

  /** This function adds a new deferred hook to the current module. Please note that this is not the
    * preferred way of adding hooks. Please use `apply` and `prefix` methods instead.
    *
    * @param priority
    *   Priority of the deferred hook. Lower priority hooks are executed later. Negative values are
    *   used internally by chext.
    * @param fn
    *   Function to be executed.
    */
  def add(priority: Int, module: Option[RawModule] = Option.empty)(fn: => Unit): Unit = {
    // This is a little bit too hacky
    val currentModule = module.getOrElse(
      Module.currentModule.get.asInstanceOf[RawModule]
    )

    hooks
      .getOrElseUpdate(
        currentModule, {
          val priorityQueue = PriorityQueue.empty[Hook]

          val atModuleBodyEndHandler = () => {
            // the atModuleBodyEnd handler
            while (priorityQueue.nonEmpty) {
              val item = priorityQueue.dequeue()
              item.fn()
            }

            hooks.remove(currentModule)
          }

          val atModuleBodyEnd = {
            val methods = classOf[RawModule].getDeclaredMethods()
            methods.filter(_.getName() == "atModuleBodyEnd").head
          }

          atModuleBodyEnd.invoke(currentModule, atModuleBodyEndHandler)

          priorityQueue
        }
      )
      .addOne(Hook(priority, () => fn))
  }

  /** Defers the execution of the code block after the end of the current module body. Unlike
    * `atModuleBodyEnd`, can be called from outside the module class. It respects the prefix.
    *
    * @param gen
    */
  def apply(fn: => Unit): Unit = {
    val currentPrefix = PrefixManager.current

    add(100) {
      PrefixManager.withAbsolute(currentPrefix) { fn }
    }
  }

  def prefix(prefix: String)(fn: => Unit): Unit = {
    PrefixManager.withRelative(prefix) {
      apply { fn }
    }
  }
}
