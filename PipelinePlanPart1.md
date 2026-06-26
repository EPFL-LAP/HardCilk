# Spawn Throughput — Step 1: Maximize Scheduler Server Throughput

**Goal:** A workload where tasks flow around the system and spawn other tasks (no
continuations for now) should flow through 4× PEs of each type at **II=1**. To do
that we widen tasks into 4-task *bundles* so a single 256-bit ring/network
transaction ferries 4 tasks, and we pack/unpack them right in front of the PEs.

This is **Step 1 only.** Optimizing the Argument system to handle more tasks comes
later.

---

## Architecture summary

### Data path (confirmed in source)

**Out (ring → PE):**
`SchedulerServer` (256b ring) → `SchedulerNetwork` (256b) → per-PE `SchedulerClient`
→ per-PE `Deque` (holds bundles) → `AxisDownscaler` (256→64) → PE
([SchedulerLocalNetwork.scala:72-76](architecture-generator/src/main/scala/Scheduler/SchedulerLocalNetwork.scala#L72), converter wired at [Scheduler.scala:290](architecture-generator/src/main/scala/Scheduler/Scheduler.scala#L290))

**In (PE → ring):**
PE → `AxisUpscaler` (64→256) → per-PE `Deque` → `SchedulerClient` → network → ring

The `SchedulerNetwork`, per-PE `Deque`, and the ring are already `taskWidth`-wide,
so they ferry bundles "for free" once `taskWidth` is widened.

### Why the per-PE serial downscaler is the right primitive

Each PE drains its own 4-task bundle over 4 cycles. With 4 PEs draining
concurrently (staggered), the aggregate is **4 tasks/cycle**, and the ring only has
to supply **1 bundle/cycle = 256b/cycle** — exactly the AXI width. The throughput
identity:

> 256b ring @ 1 bundle/cyc  ==  4 PEs @ 1 task/cyc

…holds **iff** (a) the downscaler is bubble-free (drains a bundle in exactly 4
cycles, no 5th load cycle), and (b) the per-PE Deque never starves.

---

## The data model

| width | meaning |
|---|---|
| `pePortWidth` | single task width (e.g. 64) — PE-facing port, **unchanged** |
| `taskWidth` | `bundleFactor × pePortWidth` (e.g. 256) — HBM ring + bundle payload |
| `countWidth` | `log2Ceil(bundleFactor + 1)` (3 bits for factor 4; holds 1..4) |
| `fabricWidth` | `taskWidth + countWidth` — what the **in-fabric** datapath carries |

**Key decision: the count rides as a uniform `+countWidth` sideband across the whole
fabric** (Deque, steal network, `SchedulerClient`, and the `SchedulerServer`'s
buffer + network ports). The **HBM read/write data ports stay `taskWidth`** — only
full bundles (count≡4) ever spill, so the count is implicit there and never stored.
This sidesteps the power-of-2 HBM-slot collision entirely. `SchedulerServer` is the
single place that bridges `fabricWidth` ↔ `taskWidth`.

### Partial bundles

A partial bundle (count < 4) occurs when a PE stops spawning before filling a
bundle. Handling:

1. **Accumulate across task boundaries** in the upscaler — emit a full bundle
   whenever 4 are gathered, regardless of which parent produced them. Tasks are
   independent (spawn-heavy, no continuations), so bundling arbitrary tasks is
   semantically free; the downscaler unpacks them as 4 independent tasks.
2. **Watchdog flush** (decoupled from pause): a counter resets on every input
   `fire`; if it expires with a partial pending, emit the partial with
   `count = accumulated`. This is what keeps programs that spawn only 1–2 tasks at a
   time correct. Timeout is a module parameter.

### Why low-spawn programs stay correct in Phase 1 without HBM compaction

A 1–2-task-at-a-time program has low volume → the ring never fills → it never spills
to HBM. Its partials live in the fabric/buffer, dispatched locally and flushed by
the watchdog. High-spawn programs fill bundles before the watchdog fires → full
bundles → HBM only ever sees count=4. **No HBM partial compaction is needed.**

The only theoretical corner is high *aggregate* volume with bursty *per-PE*
spawning, saturating the buffer with partials under congestion. Realistic workloads
don't hit it (congestion = high spawn rate = full bundles). Guarded by an assertion
in Phase 1; compaction stays a never-say-never fallback, **not planned work.**

---

## Phase 0 — Config plumbing (decouple the two widths)

**`architecture-generator/src/main/scala/Util/DescriptorsClean.scala`** (~line 162)
- Add `bundleFactor: Int = 1` to `TaskDescriptor` (default 1 = today's behavior,
  fully backward-compatible).
- In `validate()` (line 206): require `isPow2(bundleFactor)` and
  `isPow2(widthTask * bundleFactor) && widthTask*bundleFactor <= 1024`.

**`architecture-generator/src/main/scala/HardCilkBuilder.scala`** (lines 55/62)
- `taskWidth = task.widthTask * task.bundleFactor`
- `pePortWidth = task.widthTask`  ← the decoupling (currently both are
  `task.widthTask`, line 62).

**`architecture-generator/taskDescriptors/mfpga/triangleCountDecoupled.json`**
- Add `"bundleFactor": 4` to each PE task entry that should bundle.

---

## Phase 1 — The count sideband through the fabric

**`DequeInterface` / `SchedulerNetworkClientIO`** (in `Util/` and `Scheduler/`)
- Widen carried payload from `taskWidth` to `fabricWidth` (data low bits, count top
  `countWidth`). Mostly a width change on opaque buses.

**`architecture-generator/src/main/scala/Scheduler/SchedulerClient.scala`**
- `stolenTaskReg`, `giveTaskReg`, all `bits` → `fabricWidth` (FSM treats tasks as
  opaque; just needs the wider width).
- **Note:** `currLength`, `minLengthThresh`, `maxLengthThresh` now count *bundles*
  (≤4 tasks each) — thresholds effectively scale 4×. Flag for tuning, not a bug.

**`architecture-generator/src/main/scala/Scheduler/SchedulerLocalNetwork.scala` +
`SchedulerNetwork.scala`**
- Propagate `fabricWidth` through `taskQueues` (per-PE `Deque`, line 72), `connPE`,
  `connVSS`, `connVAS`. Pure width plumbing.

**`architecture-generator/src/main/scala/Scheduler/SchedulerServer.scala`** — the bridge
- Network ports (`connNetwork.data.availableTask` / `qOutTask`) and
  `taskQueueBuffer` → `fabricWidth`.
- **HBM ports stay `taskWidth`.**
  - `pushTaskMem` (line 285): write `buffer[taskWidth-1:0]`; **assert count==4**
    (Phase-1 guard — catches the spill-partial corner in chiseltest).
  - `popTaskMem` (line 314): read `taskWidth`, append `count=4` → `fabricWidth` into
    the buffer.
- `giveAwayTask` (line 440) emits `fabricWidth` with the real count, so a buffered
  partial is served locally with the correct count, never mangled.
- **Spill-path partial guard (replaces "compaction"):** the push FSM today bursts
  the buffer head *unconditionally* — `pushTaskMem` drains `taskQueueBuffer.io.deq`
  with no count check ([SchedulerServer.scala:285-305](architecture-generator/src/main/scala/Scheduler/SchedulerServer.scala#L285)).
  Add a guard so that when the buffer head is a **partial (count<4), it is diverted
  to `giveAwayTask` / held** rather than entering a write burst — so full bundles
  advance to the head and spill. **Wrinkle:** under congestion the FSM currently
  bypasses `giveAwayTask` ([line 247](architecture-generator/src/main/scala/Scheduler/SchedulerServer.scala#L247));
  the "serve the partial locally" escape hatch must stay reachable even when
  congested, or a head partial has nowhere to go.
- Re-verify `capBurstAtFifoEnd` / `addrShift` (lines 83/91): slot is now 32 B,
  128 slots/4KB page, `addrShift=5`. The existing 4KB fix scales but **must be
  re-run in chiseltest** (this is the area that caused the prior TCD large-size hang).

---

## Phase 2 — The converters (the actual throughput work)

**`architecture-generator/src/main/scala/AXIHelpers/AxisDataWidthConverter.scala`**
— substantial rewrite of both converters.

### `AxisDownscaler` (fabric → PE): input `fabricWidth` (data+count), output `pePortWidth`
Existing bugs to fix:
1. In `bufferData`, on `TVALID` it loads `buffer` but **never transitions to
   `writeDataToOutput`** — nothing ever comes out
   ([AxisDataWidthConverter.scala:84-88](architecture-generator/src/main/scala/AXIHelpers/AxisDataWidthConverter.scala#L84)).
2. `io.dataOut.TDATA` is driven inside the FSM (line 91) but the unconditional
   default `:= 0.U` at line 100 comes textually after it → **last-connect wins,
   data is always 0.**

New behavior:
- Emit exactly `count` lanes, then accept the next bundle.
- Skid / double-buffer so it loads the next bundle during the last drain cycle →
  **bubble-free II=1.**

### `AxisUpscaler` (PE → fabric): input `pePortWidth`, output `fabricWidth`
Existing bug to fix:
- `buffer := buffer | (...)` never clears `buffer` between bundles
  ([lines 40/43](architecture-generator/src/main/scala/AXIHelpers/AxisDataWidthConverter.scala#L40))
  → bundle N+1 ORs into bundle N's stale high bits. First bundle clean, every later
  one corrupted.

New behavior:
- **Accumulate across task boundaries**; emit count=4 when 4 gathered.
- **Watchdog flush** of a pending partial with `count = accumulated` (module param
  timeout).
- Accept input at II=1 (no bubble), clear `buffer` on emit.

### Wrapper + wiring
- **`AxisDataWidthConverter`** wrapper (line 109) and **`Scheduler.scala`**
  (lines 282/286/350): the wide side now connects to a `fabricWidth` Deque port; the
  narrow side stays `pePortWidth`. Update instantiations and the
  `taskWidth==pePortWidth` wire-passthrough branch (no longer the bundling case).

### Audit other converter users (shared-bug-fix regression check)
Fixing `AxisDownscaler` changes behavior for any *downscaling* instantiation:
- `ArgumentNotifier.scala:103` — `AxisDataWidthConverter(pePortWidth=64, addrWidth)`;
  if `addrWidth < 64` this is a downscaler and its behavior changes. Verify.
- `Allocator.scala:100` — `(addrWidth, pePortWidth)`. Check direction.
These are **not** part of bundling but the shared fix can regress them.

---

## Phase 2b — Pipeline the SchedulerClient (measured target)

The intake loop `init → takeInTask → pushTask → init`
([SchedulerClient.scala:60-102](architecture-generator/src/main/scala/Scheduler/SchedulerClient.scala#L60))
is ~3 states/transaction. Post-bundling a transaction moves 4 tasks, so the rate
that matters is **cycles-per-bundle vs. the downscaler's 4-cycles-per-bundle drain**:

- ~3 cyc/bundle = 1.33 tasks/cyc supplied vs. 1 consumed → keeps up, but only ~33%
  margin, and **only if doing intake exclusively.**
- Interleaving **serve-steal-requests** with intake drops the duty cycle below 1.0 →
  the PE starves.

**Plan:** pipeline the client toward II=1–2 per *bundle*. **Set the exact target
from a measurement** (profile the PE port: if II>1 there comes from the client vs.
Deque vs. converter). Lower-risk than the converters; do it after they're bubble-free.

---

## Phase 3 — Verification (chiseltest, before any HW)

- **Converter unit tests:** sustained II=1 full stream; cross-task accumulation;
  watchdog flush of 1/2/3-task partials; count correctness; backpressure both sides.
- **Deque / network:** `fabricWidth` round-trips count intact.
- **SchedulerServer:** full-bundle HBM spill + round-trip (count=4); partial served
  via `giveAwayTask` with correct count; the count==4 spill assertion; **re-run the
  4KB-boundary test at the new slot size.**
- **End-to-end TCD** at `bundleFactor=4`: correctness vs. reference; aggregate
  4 tasks/cycle into 4 PEs; **plus a low-spawn variant** to exercise the watchdog.

---

## Deferred / out of scope for Step 1

- **HBM partial compaction** — only for the pathological high-volume + bursty corner
  the Phase-1 assertion would flag. Not planned.
- **Argument system** scaling — the next step, separate effort.

---

## Suggested build order

1. **Phase 0** config plumbing (small, unblocks everything).
2. **Phase 2 converters + their unit tests in isolation** (riskiest piece; fully
   testable standalone at a fixed `fabricWidth` before disturbing the network).
3. **Phase 1** sideband plumbing through Deque / network / SchedulerServer.
4. **Phase 2b** client pipelining (after profiling).
5. **Phase 3** end-to-end TCD verification.
