/** Package-level alias so that the many `import Scheduler._` sites can name the scheduler
  * abstraction without having to spell out `_root_.Scheduler.HardCilkSchedulerLike.SchedulerLike`
  * (the package and the classic `Scheduler` class share a name, which makes the qualified path
  * resolve to the class's companion object instead of the package).
  */
package object Scheduler {
  type SchedulerLike = chisel3.Module with HardCilkSchedulerLike
}
