package Scheduler.tests

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import Scheduler.{BufferServerInput, GlobalTaskBuffer, SchedulerNetwork, SpawnerServer}

import scala.Predef.{assert => sAssert, _}
import scala.collection.mutable

/** A slice of the real outside-spawn network: `slots` copies of
  * (GlobalTaskBuffer -> BufferServerInput -> SpawnerServer) sharing one ring, wired exactly as
  * Scheduler.scala wires them. Each spawner's PE-side port (connNetwork_master) is broken out so
  * the test can play the role of the PE-local steal ring.
  *
  * This is the configuration the per-module tests cannot reach: the hardware failure was that all
  * task supply arrives at slot 0 and never reaches slots 1..n-1, which is only observable with a
  * real ring in the middle.
  */
class SlotDebug extends Bundle {
  val peerDemand = Bool() // a ctrl token is in our ring slot, as the spawner sees it
  val serveValid = Bool() // we are answering it
  val stealReqV = Bool() // we are asking the ring for work
  val stealReqR = Bool() // the ring slot is free for our request
  val availV = Bool() // a task is passing us on the data ring
  val availR = Bool() // we would take it
  val qOutV = Bool() // we are injecting into the data ring
  val qOutR = Bool() // the data slot is free for injection
  val srcQOutV = Bool() // our task source has a task for us
}

class OutsideSpawnRingHarness(taskWidth: Int, slots: Int) extends Module {
  val io = IO(new Bundle {
    val srcIn = Vec(slots, Flipped(DecoupledIO(UInt(taskWidth.W))))
    // Stand-in for the PE-local steal ring at each spawner.
    val peAsking = Input(Vec(slots, Bool())) // a PE steal token is passing our node
    val peReady = Input(Vec(slots, Bool())) // the PE ring data slot is free
    val peValid = Output(Vec(slots, Bool()))
    val peBits = Output(Vec(slots, UInt(taskWidth.W)))
    val peServed = Output(Vec(slots, Bool())) // we consumed a PE steal token this cycle
    val dbg = Output(Vec(slots, new SlotDebug))
  })

  val net = Module(new SchedulerNetwork(taskWidth, slots, Array(0)))
  val bufs = Seq.fill(slots)(Module(new BufferServerInput(taskWidth)))
  val spawners = Seq.fill(slots)(Module(new SpawnerServer(taskWidth)))
  val sources = Seq.fill(slots)(Module(new GlobalTaskBuffer(taskWidth, 1)))

  for (i <- 0 until slots) {
    bufs(i).io.connNetwork_slave <> net.io.connSS(i)
    bufs(i).io.connSpawnerServer <> spawners(i).io.connNetwork_slave
    bufs(i).io.connTaskSource <> sources(i).io.connStealNtw
    sources(i).io.in <> io.srcIn(i)

    val m = spawners(i).io.connNetwork_master
    m.ctrl.serveStealReq.ready := io.peAsking(i)
    m.data.qOutTask.ready := io.peReady(i)
    io.peValid(i) := m.data.qOutTask.valid
    io.peBits(i) := m.data.qOutTask.bits
    io.peServed(i) := m.ctrl.serveStealReq.valid
    // The spawner never pulls from, nor asks, the PE ring -- drive the unused inputs.
    m.data.availableTask.valid := false.B
    m.data.availableTask.bits := 0.U
    m.ctrl.stealReq.ready := false.B

    val s = spawners(i).io.connNetwork_slave
    io.dbg(i).peerDemand := s.ctrl.serveStealReq.ready
    io.dbg(i).serveValid := s.ctrl.serveStealReq.valid
    io.dbg(i).stealReqV := s.ctrl.stealReq.valid
    io.dbg(i).stealReqR := s.ctrl.stealReq.ready
    io.dbg(i).availV := s.data.availableTask.valid
    io.dbg(i).availR := s.data.availableTask.ready
    io.dbg(i).qOutV := s.data.qOutTask.valid
    io.dbg(i).qOutR := s.data.qOutTask.ready
    io.dbg(i).srcQOutV := sources(i).io.connStealNtw.data.qOutTask.valid
  }
}

class OutsideSpawnRingTests extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "outside-spawn ring"

  private val taskWidth = 32
  private val slots = 4

  /** Drives the harness for `cycles`, feeding `taskCount` tasks into slot `srcSlot` only.
    * Returns per-slot delivery counts and the multiset of every task value delivered.
    */
  private def run(
      dut: OutsideSpawnRingHarness,
      taskCount: Int,
      cycles: Int,
      srcSlot: Int,
      peAsking: (Int, Int) => Boolean,
      peReady: (Int, Int) => Boolean // (slot, cycle) => ready
  ): (Array[Int], mutable.ArrayBuffer[BigInt]) = {
    val delivered = Array.fill(slots)(0)
    val seen = mutable.ArrayBuffer.empty[BigInt]

    for (i <- 0 until slots) {
      dut.io.srcIn(i).valid.poke(false.B)
      dut.io.srcIn(i).bits.poke(0.U)
    }

    var nextTask = 1
    for (cycle <- 0 until cycles) {
      val readyNow = Array.tabulate(slots)(i => peReady(i, cycle))
      for (i <- 0 until slots) {
        dut.io.peReady(i).poke(readyNow(i).B)
        dut.io.peAsking(i).poke(peAsking(i, cycle).B)
      }

      val feeding = nextTask <= taskCount
      dut.io.srcIn(srcSlot).valid.poke(feeding.B)
      if (feeding) dut.io.srcIn(srcSlot).bits.poke(nextTask.U)

      // Sample after all inputs are driven: these are combinational outputs.
      for (i <- 0 until slots) {
        if (readyNow(i) && dut.io.peValid(i).peek().litToBoolean) {
          delivered(i) += 1
          seen += dut.io.peBits(i).peek().litValue
        }
      }
      val accepted = feeding && dut.io.srcIn(srcSlot).ready.peek().litToBoolean

      dut.clock.step()
      if (accepted) nextTask += 1
    }
    dut.io.srcIn(srcSlot).valid.poke(false.B)
    (delivered, seen)
  }

  it should "spill to peers when the local PE cannot drain" in {
    test(new OutsideSpawnRingHarness(taskWidth, slots)) { dut =>
      dut.clock.setTimeout(0)

      // Slot 0 receives every task but its PE is not asking and cannot accept, so it can never
      // deliver locally and makes no promises against its queue. Slots 1..3 are idle and hungry.
      // (A PE that IS asking would have its tasks reserved for it, which is correct -- the ring
      // frees up eventually and the promise is honoured.) Before the fixes, slot 0 would
      // simply fill up and hold everything: its queue never signalled "full" to BufferServerInput,
      // it kept its own steal request pinned in the node's ctrl slot so no peer token ever reached
      // it, and BufferServerInput consumed the peers' requests on the task source's behalf.
      val taskCount = 64
      val (delivered, seen) = run(
        dut,
        taskCount = taskCount,
        cycles = 1500,
        srcSlot = 0,
        peAsking = (slot, _) => slot != 0,
        peReady = (slot, _) => slot != 0
      )

      info(s"delivered per slot: ${delivered.mkString(", ")}")
      sAssert(delivered(0) == 0, s"slot 0 cannot deliver locally, got ${delivered(0)}")
      val toPeers = delivered.drop(1).sum
      sAssert(toPeers > 0, "no task ever reached a peer spawner -- distribution is broken")
      sAssert(
        seen.distinct.size == seen.size,
        s"a task was delivered more than once: ${seen.diff(seen.distinct).distinct.mkString(", ")}"
      )
      sAssert(toPeers == taskCount, s"expected all $taskCount tasks to drain, got $toPeers")
    }
  }

  it should "keep work local when the local PE is asking and able to take it" in {
    test(new OutsideSpawnRingHarness(taskWidth, slots)) { dut =>
      dut.clock.setTimeout(0)

      // Same supply, but now slot 0's own PE can accept. Our own PE outranks a peer, so the work
      // should stay local rather than being handed sideways.
      val taskCount = 64
      val (delivered, seen) = run(
        dut,
        taskCount = taskCount,
        cycles = 1200,
        srcSlot = 0,
        peAsking = (_, _) => true,
        peReady = (_, _) => true
      )

      info(s"delivered per slot: ${delivered.mkString(", ")}")
      sAssert(
        delivered(0) == taskCount,
        s"local PE should have taken all $taskCount, got ${delivered.mkString(", ")}"
      )
      sAssert(seen.distinct.size == seen.size, "a task was delivered more than once")
    }
  }

  it should "conserve every task when the local PE drains slowly" in {
    test(new OutsideSpawnRingHarness(taskWidth, slots)) { dut =>
      dut.clock.setTimeout(0)

      // Slot 0 asks only 1 cycle in 8, so its unrequested surplus has to find the idle peers.
      // `peReady` models availability of the PE data-ring slot, not PE demand: holding peAsking high
      // while peReady is low legitimately lets the spawner accept and reserve those requests, in
      // which case the tasks must remain local rather than spill.
      val taskCount = 96
      val (delivered, seen) = run(
        dut,
        taskCount = taskCount,
        cycles = 2000,
        srcSlot = 0,
        peAsking = (slot, cycle) => if (slot == 0) cycle % 8 == 0 else true,
        peReady = (_, _) => true
      )

      info(s"delivered per slot: ${delivered.mkString(", ")}")
      sAssert(
        seen.distinct.size == seen.size,
        s"duplicated: ${seen.diff(seen.distinct).distinct.mkString(", ")}"
      )
      sAssert(
        seen.size == taskCount,
        s"expected $taskCount delivered exactly once, got ${seen.size}"
      )
      sAssert(
        seen.map(_.toInt).toSet == (1 to taskCount).toSet,
        "delivered set does not match the injected set"
      )
      sAssert(delivered.drop(1).sum > 0, "slow local PE did not spill any work to peers")
    }
  }

  /** The countDecoupled loop shape. A large burst is injected at the head of the chain. Each PE is
    * slow, so it stops issuing steal requests and its spawner backs up; the surplus has to migrate
    * down the chain. A task serviced by a PE re-enters at that same slot's own task source, exactly
    * like a continuation coming back to the PE that produced it. The last slot in the chain is a
    * sink and never recirculates, so the system has somewhere to drain to.
    *
    * Once injection stops and recirculation is switched off, every task in flight must come out.
    */
  it should "distribute a recirculating workload and fully drain it" in {
    test(new OutsideSpawnRingHarness(taskWidth, slots)) { dut =>
      dut.clock.setTimeout(0)

      val externalTasks = 300
      val peCapacity = 4 // how many tasks a PE will hold
      val askBelow = 2 // it issues steal requests below this occupancy
      val servicePeriod = 4 // one task retired every N cycles
      val recircDelay = 3 // and it reappears at our own source N cycles later
      val recirculateUntil = 12000
      val maxCycles = 30000
      val sink = slots - 1 // the last slot never sends work back round

      var nextId = 1
      var extRemaining = externalTasks
      val injected = mutable.Set.empty[BigInt]
      val delivered = mutable.ArrayBuffer.empty[BigInt]
      val perSlot = Array.fill(slots)(0)

      val peQueue = Array.fill(slots)(mutable.Queue.empty[BigInt])
      val pending = Array.fill(slots)(mutable.Queue.empty[BigInt])
      val timed = mutable.ArrayBuffer.empty[(Int, Int, BigInt)] // (releaseCycle, slot, id)
      val nextServiceAt = Array.fill(slots)(0)
      var lastDeliveryCycle = 0

      for (cycle <- 0 until maxCycles) {
        val recirculating = cycle < recirculateUntil

        timed.filter(_._1 <= cycle).foreach { case (_, slot, id) => pending(slot).enqueue(id) }
        timed --= timed.filter(_._1 <= cycle)

        // Keep the head of the chain supplied from the external burst.
        if (extRemaining > 0 && pending(0).size < 2) {
          pending(0).enqueue(BigInt(nextId)); nextId += 1; extRemaining -= 1
        }

        for (i <- 0 until slots) {
          dut.io.peReady(i).poke((peQueue(i).size < peCapacity).B)
          dut.io.peAsking(i).poke((peQueue(i).size < askBelow).B)
          dut.io.srcIn(i).valid.poke(pending(i).nonEmpty.B)
          if (pending(i).nonEmpty) dut.io.srcIn(i).bits.poke(pending(i).head.U)
        }

        for (i <- 0 until slots) {
          if (peQueue(i).size < peCapacity && dut.io.peValid(i).peek().litToBoolean) {
            val id = dut.io.peBits(i).peek().litValue
            delivered += id
            perSlot(i) += 1
            peQueue(i).enqueue(id)
            lastDeliveryCycle = cycle
          }
          if (pending(i).nonEmpty && dut.io.srcIn(i).ready.peek().litToBoolean) {
            injected += pending(i).dequeue()
          }
        }

        // Retire work and (until we switch it off) send a fresh task back to our own source.
        for (i <- 0 until slots) {
          if (cycle >= nextServiceAt(i) && peQueue(i).nonEmpty) {
            peQueue(i).dequeue()
            nextServiceAt(i) = cycle + servicePeriod
            if (recirculating && i != sink) {
              timed += ((cycle + recircDelay, i, BigInt(nextId)))
              nextId += 1
            }
          }
        }

        dut.clock.step()
      }

      info(s"delivered per slot: ${perSlot.mkString(", ")}")
      info(s"injected=${injected.size} delivered=${delivered.size} lastDelivery=$lastDeliveryCycle")

      sAssert(
        perSlot.forall(_ > 0),
        s"a PE never received any work: ${perSlot.mkString(", ")}"
      )
      sAssert(
        delivered.distinct.size == delivered.size,
        s"duplicated: ${delivered.diff(delivered.distinct).distinct.take(8).mkString(", ")}"
      )
      sAssert(
        pending.forall(_.isEmpty),
        s"a task source never drained: ${pending.map(_.size).mkString(", ")}"
      )
      sAssert(
        delivered.toSet == injected.toSet,
        s"lost in the network: ${(injected -- delivered).take(8).mkString(", ")}"
      )
      sAssert(
        lastDeliveryCycle < recirculateUntil + 2000,
        s"still delivering at cycle $lastDeliveryCycle -- did not quiesce after recirculation stopped"
      )
    }
  }

  it should "fan work out past a spawner whose PE is not asking" in {
    test(new OutsideSpawnRingHarness(taskWidth, slots)) { dut =>
      dut.clock.setTimeout(0)

      // Slot 0 is the only source and its PE never asks, so everything has to spill. Slot 1's PE is
      // in the state a real PE spends most of its time in: it can accept a task pushed at it, but
      // issues no steal request because its local queue is satisfied. Slots 2 and 3 are working
      // PEs -- they ask while below threshold and accept while not full, so asking always implies
      // able to accept, as it does in the real design.
      //
      // The work must travel past slot 1 and reach the PEs that actually asked.
      val externalTasks = 200
      val peCapacity = 4
      val askBelow = 2
      val servicePeriod = 4

      var nextId = 1
      var extRemaining = externalTasks
      val injected = mutable.Set.empty[BigInt]
      val delivered = mutable.ArrayBuffer.empty[BigInt]
      val perSlot = Array.fill(slots)(0)
      val peQueue = Array.fill(slots)(mutable.Queue.empty[BigInt])
      val pending = mutable.Queue.empty[BigInt]
      val nextServiceAt = Array.fill(slots)(0)

      for (cycle <- 0 until 20000) {
        if (extRemaining > 0 && pending.size < 2) {
          pending.enqueue(BigInt(nextId)); nextId += 1; extRemaining -= 1
        }
        for (i <- 0 until slots) {
          dut.io.peReady(i).poke((peQueue(i).size < peCapacity).B)
          // slots 0 and 1 never ask; 2 and 3 ask only while they have room to take it
          dut.io.peAsking(i).poke((i >= 2 && peQueue(i).size < askBelow).B)
          dut.io.srcIn(i).valid.poke((i == 0 && pending.nonEmpty).B)
        }
        if (pending.nonEmpty) dut.io.srcIn(0).bits.poke(pending.head.U)

        for (i <- 0 until slots) {
          if (peQueue(i).size < peCapacity && dut.io.peValid(i).peek().litToBoolean) {
            val id = dut.io.peBits(i).peek().litValue
            delivered += id; perSlot(i) += 1; peQueue(i).enqueue(id)
          }
        }
        if (pending.nonEmpty && dut.io.srcIn(0).ready.peek().litToBoolean) injected += pending.dequeue()

        for (i <- 0 until slots) {
          if (cycle >= nextServiceAt(i) && peQueue(i).nonEmpty) {
            peQueue(i).dequeue(); nextServiceAt(i) = cycle + servicePeriod
          }
        }
        dut.clock.step()
      }

      info(s"delivered per slot: ${perSlot.mkString(", ")}")
      sAssert(delivered.distinct.size == delivered.size, "a task was delivered more than once")
      sAssert(delivered.toSet == injected.toSet, s"lost ${(injected -- delivered).size} tasks")
      sAssert(perSlot(2) > 0, "work never reached slot 2")
      sAssert(perSlot(3) > 0, "work never reached slot 3")
      sAssert(
        perSlot(2) + perSlot(3) > perSlot(1),
        s"work piled into the non-asking spawner instead of the asking ones: ${perSlot.mkString(", ")}"
      )
    }
  }

  it should "hold work rather than shedding it, then drain once the PEs ask" in {
    test(new OutsideSpawnRingHarness(taskWidth, slots)) { dut =>
      dut.clock.setTimeout(0)

      // Nobody asks for the first stretch. A spawner must NOT pre-emptively dump its queue at a PE
      // that never asked -- force exists only to make room for an arriving task. Once the PEs do
      // start asking, everything must come out.
      val taskCount = 48
      val (delivered, seen) = run(
        dut,
        taskCount = taskCount,
        cycles = 2000,
        srcSlot = 0,
        peAsking = (_, cycle) => cycle > 900,
        peReady = (_, _) => true
      )

      info(s"delivered per slot: ${delivered.mkString(", ")}")
      sAssert(seen.distinct.size == seen.size, "a task was delivered more than once")
      sAssert(
        seen.size == taskCount,
        s"work stranded after the PEs started asking: expected $taskCount, got ${seen.size}"
      )
    }
  }
}
