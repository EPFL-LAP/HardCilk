# Fixes from the `v80-support` branch that belong on `main`

Most of `v80-support` is Alveo V80 support. It sits behind `--board v80` and opt-in `HC_*` knobs, and with the
knobs unset it leaves the U55C output byte-identical. The changes below are different: they fix bugs or build
breakage that affect **every board**, including the U55C flow that `main` ships. They are split into separate
commits on `v80-support` so they can be cherry-picked onto `main` on their own (see the commit list at the end).

## 1. Scheduler/spawner ring bursts ran past the end of the ring (hardware, all boards)

Files: `architecture-generator/src/main/scala/Scheduler/SchedulerServer.scala` and `SpawnerServer.scala`.

- **Bug.** The virtual-queue servers issue AXI INCR bursts at `fifoTail` (writes) and `fifoHead` (reads) without
  clamping them at `maxLength`. The ring wraps at `maxLength`, but an INCR burst is linear, so:
  - the part of a burst past the ring end lands outside the ring, overwriting whatever the host allocated next;
  - the wrapped slots are read stale.
- **Symptoms.** These depend on queue sizes and occupancy:
  - deadlocks;
  - lost or duplicated tasks;
  - fib(N) hanging once the virtual queues were grown.
- **Fix.** Every burst is clamped to the slots left before the ring end, and the next burst continues at index 0:
  - `SpawnerServer`: `writeCount` = `min(queue count, maxLength - fifoTail)`, and `readCount` = `min(currLen,
    queueDepth, maxLength - fifoHead)`;
  - `SchedulerServer`: `pushCount` / `popCount`, the same way for its 16-beat pushes and pops.
- **Validated.** fib(35) and fib(40) on the card (fib(40) = 102334155 in 19.4 s), and every CilkWeave benchmark
  bit-exact in QuestaSim and on the card.

## 2. Host driver: scheduler-ring growth re-ran consumed tasks (software, all boards)

Files: `software_template/driver/src/hardCilkDriver.cpp` (`manageSchedulerServer`) and
`software_template/driver/include/hardCilkDriver.tpp` (`initSystem`).

- **Growth copied the whole ring.** When a scheduler server paused, the driver copied the entire old ring into a
  ring twice the size and declared it full (`fifoTail = maxLength`, `currLen = maxLength`). The server pauses when
  fewer than a burst of slots are free, not only when all slots are live. So the stale, already-consumed slots
  between `fifoTail` and `fifoHead` became tasks again and ran a second time.
  - **Fix.** Copy only the `currLen` live entries, from `fifoHead` and wrapping at `maxLength`.
  - **Check.** Validate the ring state first (`(head + currLen) % maxLength == tail`).
- **Bursts need a 16-aligned tail.** The SchedulerServer pushes fixed 16-task bursts at `fifoTail`, so `fifoTail`
  must stay a multiple of 16.
  - **Growth path.** The new head is padded so that the new tail lands on a 16-entry boundary.
  - **Root tasks.** `initSystem` places them the same way: `fifoHead` = padding, `fifoTail` = padding + root count.
- **A host-set tail equal to `maxLength`** is treated as slot 0.

## 3. Build: `chict` dependency removed (build, all boards)

Files: `architecture-generator/build.sbt` and `architecture-generator/src/main/scala/HardCilk_old.scala`.

`chict 0.1.0` is not resolvable from a clean machine. Its only user was `HardCilk_old.scala`, which is dead code
and is now commented out entirely. The dependency line is commented out in `build.sbt`. The generator builds and
every flow runs without it.

## 4. Requires the chext round-robin arbiter fix (hardware, all boards)

HardCilk's interconnect muxes (HBMInterconnect, task/argument distribution) use chext's `RRChooser`.

- **Bug.** With a non-power-of-two input count (3, 5, 7, 12, 13, ... inputs), once the last input won it kept
  winning for as long as it stayed valid. It starved every other input.
- **Symptom.** In QuestaSim, 11 of 12 pageRank_overlap PEs froze after 189 tasks each.
- **Where it was fixed.** In chext itself (`src/main/scala/chext/elastic/Chooser.scala`; see chext's
  `FIXES.md`). It is currently re-published locally as chext **0.1.1**, the same version number.
- **Action for `main`.** Bump chext to a new version (e.g. 0.1.2) with the fix, and update the
  `"hdlstuff" %% "chext"` line in `build.sbt`. Otherwise a machine with the old 0.1.1 in its ivy cache silently
  builds the buggy arbiter.

## Not for `main` (V80 only, opt-in)

These stay on `v80-support` unless `main` wants V80 support:
- `Util/Board.scala`, `--board`;
- `TclGeneratorMemPEsV80.scala`, `TclQuestaSimV80.scala`, `TclV80Common.scala`, `V80Lutram.scala`;
- `TclHbmex.scala` / `hbmex_v80/`;
- the `Floorplan`/`DeviceProfile` V80 profile;
- the QDMA host backend (`memIO_qdma.h`, `hbm_stripe.h`, `v80_shim/`, `tools/hc_v80_probe`, `scripts/v80_*`);
- the SLR-local scheduler, argument notifier and NoC-PE pools (`SlrLocal*.scala`, `NocPePolicy.scala`, and the
  `HC_SCHED_SLR_LOCAL` / `HC_NOC_*` paths in `HBMInterconnect`, `HardCilkBuilder` and `HardCilkEmitterUtil`);
- the generated-header and README additions for V80.

Every V80 knob is documented in `TclV80Common.scala` (`HC_*` env vars). With all of them unset, the U55C output
is unchanged.
