# HardCilk telemetry trace (`.bin`) format

This document fully specifies the binary trace emitted by the **telemetry watcher**
in the `triangleCountDecoupled` benchmark, so a viewer can be built from scratch.

The host driver writes the trace to
`/tmp/triangleCountDecoupled_telemetry_<YYYYMMDD_HHMMSS>.bin` at the end of a run
(`dumpTelemetry` in `TriangleCountDecoupledDriver.h`).

---

## 1. What the trace is

A **free-running hardware "watcher"** kernel (HLS, `ap_ctrl_none`) snapshots the
queue handshake status of every monitored processing element (PE) and writes one
**record** to HBM **each time any monitored bit changes** (a state-change trace,
not a periodic sample). Each record carries a full snapshot of all PEs plus an
absolute cycle timestamp. Between two consecutive records, every PE's state is
unchanged — i.e. the trace is a list of edges; hold-last-value to reconstruct a
continuous timeline.

The watcher source is
`hls-processing-elements/mfpga/triangleCountDecoupled/memAccess.cpp` (function
`watcher`).

---

## 2. File layout

- The file is a contiguous array of fixed-size **64-byte records**, no header, no
  footer, no padding between records.
- `record_count = filesize / 64`.
- All multi-byte integers are **little-endian**.
- Records are in **emission order** (monotonic, increasing `cycle_count`).

The host already trims the file to exactly the populated records (it locates the
first populated record and writes the contiguous populated run — see §7 on the
`write_idx` carry-over), so every record in the file is valid.

---

## 3. Record layout (64 bytes)

| Offset | Size | Field            | Notes                                             |
|-------:|-----:|------------------|---------------------------------------------------|
| 0      | 16   | `cont0[0..3]`    | 4 PEs × 4 bytes — see §4                           |
| 16     | 16   | `memReader[0..3]`| 4 PEs × 4 bytes                                   |
| 32     | 16   | `reentry0[0..3]` | 4 PEs × 4 bytes                                   |
| 48     | 2    | `padding`        | always 0                                          |
| 50     | 6    | (alignment hole) | always 0 (`cycle_count` is 8-byte aligned)        |
| 56     | 8    | `cycle_count`    | `uint64` LE — absolute cycle timestamp (see §6)   |

Each **PE occupies 4 consecutive bytes**, one byte per raw AXIS handshake bit
(the bytes are 0x00 or 0x01). Within a PE:

| PE byte | Field        | Side     | Meaning (1 = true)                                  |
|--------:|--------------|----------|-----------------------------------------------------|
| +0      | `in_valid`   | consumer | upstream is offering a task to this PE              |
| +1      | `in_ready`   | consumer | this PE is ready to accept on its input             |
| +2      | `out_valid`  | producer | this PE is offering a result on its output          |
| +3      | `out_ready`  | producer | the downstream consumer is ready to accept          |

These are the **raw `TVALID`/`TREADY`** of each PE's input and output AXIS. Note
the perspective flips between the two queues: on the **input** queue the PE is the
*consumer* (`in_ready` is the PE's own readiness; `in_valid` comes from upstream);
on the **output** queue the PE is the *producer* (`out_valid` is the PE's own
output; `out_ready` comes from downstream). A **transfer** on a queue happens on
any cycle where `valid && ready`.

> Why 4 bytes/PE and 64 bytes/record (not bit-packed)? The HLS struct uses
> `ap_uint<1>` fields; the `#pragma HLS AGGREGATE compact=bit` only packs the
> kernel's internal register — the **AXI store to HBM uses the struct's natural
> layout**, so each `ap_uint<1>` lands in its own byte. This 64-byte layout is
> verified against on-device traces; do not assume the 128-bit packed form.

### PE indexing

12 PEs total: **4 of each of 3 task types**, in this fixed order:

- `cont0[0..3]`  — `whileLoopMain_reentry0_cont0` PEs (continuation: compares
  `A[i]`/`B[j]`, updates the match count, advances `i`/`j`).
- `memReader[0..3]` — `memReader` PEs (read `A[i]` and `B[j]` from HBM).
- `reentry0[0..3]` — `whileLoopMain_reentry0` PEs (loop re-entry: spawns the next
  `memReader`/`cont0` work or writes the final result).

Each PE's **input** queue is fed by its scheduler; its **output** queue feeds the
next stage in the task graph.

---

## 4. Bit semantics (raw AXIS handshakes)

The four bytes are the **raw `TVALID`/`TREADY`** of the PE's two AXIS queues,
sampled coherently (see §7). Everything the viewer needs is derived from them:

```
# input queue (PE is the CONSUMER):
WAITING  = !in_valid                 # nothing offered -> idle
STALLED  =  in_valid && !in_ready    # task offered, PE can't accept it (busy/blocked)
ACTIVE   =  in_valid &&  in_ready    # a task is being accepted THIS cycle
consumed =  in_valid &&  in_ready    # = a transfer in (one task taken)

# output queue (PE is the PRODUCER):
pushed       = out_valid &&  out_ready   # a transfer out (one result emitted)
out_stalled  = out_valid && !out_ready   # PE has output but downstream not ready
out_idle     = !out_valid                # PE offering nothing
```

Unlike `empty`/`full`, `valid` and `ready` are **independent** — both being 1 is
the *normal* "transfer" case, not an anomaly.

---

## 5. The three PE states the viewer must show

Derive each PE's state **from its input handshake** (`in_valid`, `in_ready`):

| State        | Condition                      | Meaning                                                 |
|--------------|--------------------------------|---------------------------------------------------------|
| **WAITING**  | `in_valid == 0`                | idle — no incoming task to work on                      |
| **STALLED**  | `in_valid == 1 && in_ready==0` | a task is available but the PE isn't accepting it (busy)|
| **ACTIVE**   | `in_valid == 1 && in_ready==1` | a task is being accepted this cycle (PE consuming work) |

These three are exhaustive and mutually exclusive (they partition the `in_valid`/
`in_ready` space). Each `ACTIVE` cycle is also exactly a **task-consumed** event.

The **output** handshake distinguishes *why* / what the PE is doing downstream:

- `pushed` (`out_valid && out_ready`) → a result was handed off this cycle (mark
  task-push events; count them for throughput).
- `out_stalled` (`out_valid && !out_ready`) → the PE produced a result but the
  **downstream is not ready** (backpressure). A PE that is `STALLED` on input while
  `out_stalled` is set is blocked by downstream congestion, not its own compute.

Suggested rendering: one horizontal timeline per PE (12 lanes, grouped by type),
colored WAITING / ACTIVE / STALLED, with tick marks for `pushed` events and an
overlaid sub-track for `out_stalled` (downstream backpressure).

---

## 6. Time / reconstructing a continuous timeline

- `cycle_count` is an **absolute** free-running counter that starts at FPGA
  program/reset time and ticks **every kernel clock** (kernel clock is 100 MHz in
  the current build → 10 ns/tick).
- It is **not** zero at the first record: its value is however many cycles elapsed
  between program load and the first monitored state change (typically hundreds of
  millions — host setup time). Treat the **first record's `cycle_count` as t0** and
  subtract it for a run-relative timeline.
- Each record is an **edge**: the state it describes holds from this record's
  `cycle_count` until the next record's `cycle_count`. So a PE's state at time `t`
  is the state in the most recent record with `cycle_count <= t`.
- Duration of a state = `next.cycle_count - this.cycle_count` cycles. The last
  record has no successor; treat it as the end of the trace.

Pseudocode to build per-PE intervals:

```python
prev_cycle = None
state = [None]*12          # last known state per PE
for rec in records:        # in file order
    if prev_cycle is not None:
        emit_intervals(prev_cycle, rec.cycle_count, state)  # close [prev,rec)
    state = decode_states(rec)   # 12 states from the 12 PEs
    prev_cycle = rec.cycle_count
```

---

## 7. Gotchas a viewer/author must handle

1. **`write_idx` persists across runs until the FPGA is reprogrammed.** The watcher
   is free-running; its write index is a `static` that only resets when the device
   is reprogrammed (XRT skips reprogramming if the same xclbin is reloaded). The
   host trims the file to the current run's contiguous populated region, so the
   **file itself always starts at the run's first record**. For a trace that begins
   at the very start of HBM (and to avoid any ambiguity), run `xrt-smi reset
   --device <bdf>` before the program; then the populated run starts at index 0.

2. **Coherent sampling (no skew).** All status bits pass through a single uniform
   register stage in the hardware (one `RegNext` in `connectWatcher`, identical
   1-cycle delay on every bit), and each queue's 2 bits are read by the watcher in
   a single atomic access — so within a record all bits are from the **same
   cycle**. `valid`/`ready` are independent, so any combination is legitimate
   (e.g. `valid && ready` = a transfer). No both-empty-and-full edge case exists in
   this representation. (The whole record is delayed a fixed 1 cycle vs the PEs;
   irrelevant for a state timeline.)

3. **Workload determines which states appear.** `STALLED` (`in_valid && !in_ready`)
   only occurs when tasks arrive faster than a PE consumes them. In light runs
   (e.g. size=100) PEs are supply-bound: mostly WAITING, some ACTIVE, ~0% STALLED.
   To exercise/observe stalls and backpressure, run a saturating workload — larger
   `size` and/or many concurrent instances (the driver's `num_instances` arg).

4. **`padding` and the alignment hole (bytes 48..55) are always 0.** Don't rely on
   them for anything; they're not part of the signal.

5. **Endianness / record size are fixed** (LE, 64 B). Validate `filesize % 64 == 0`.

---

## 8. How to generate a trace

```sh
# one-time per power cycle, for a clean trace starting at index 0:
xrt-smi reset --device 0000:01:00.1 --force

# run: <xclbin> <size> <num_instances>
#   size          = problem size per instance
#   num_instances = independent root tasks launched concurrently (more PE activity)
XCL_EMULATION_MODE= \
  ./triangleCountDecoupled_xrt triangleCountDecoupled.xclbin 100 100
# -> prints: [telemetry] wrote <N> bundles ... to: /tmp/...telemetry_<ts>.bin
```

For dense, multi-PE traces use a large `num_instances` (e.g. 100). Each record is
a global snapshot, so all 12 PEs are always present in every record.

---

## 9. Reference decoder (Python)

```python
import struct, sys

REC = 64
TYPES = [("cont0", 0), ("memReader", 16), ("reentry0", 32)]

def decode(path):
    data = open(path, "rb").read()
    assert len(data) % REC == 0, "file size must be a multiple of 64"
    out = []
    for off in range(0, len(data), REC):
        rec = data[off:off+REC]
        cyc = struct.unpack_from("<Q", rec, 56)[0]
        pes = {}
        for name, base in TYPES:
            for i in range(4):
                in_valid, in_ready, out_valid, out_ready = rec[base+i*4 : base+i*4+4]
                if not in_valid:   st = "WAITING"
                elif not in_ready: st = "STALLED"
                else:              st = "ACTIVE"      # == task consumed this cycle
                pes[f"{name}[{i}]"] = dict(
                    state=st,
                    in_valid=in_valid, in_ready=in_ready,
                    out_valid=out_valid, out_ready=out_ready,
                    consumed=bool(in_valid and in_ready),
                    pushed=bool(out_valid and out_ready),
                    out_stalled=bool(out_valid and not out_ready))
        out.append(dict(cycle=cyc, pes=pes))
    return out

if __name__ == "__main__":
    recs = decode(sys.argv[1])
    t0 = recs[0]["cycle"]
    for r in recs[:20]:
        busy = [k for k, v in r["pes"].items() if v["state"] != "WAITING"]
        print(f"t+{r['cycle']-t0:>8} active/stalled: {busy}")
```

---

## 10. Source-of-truth references

- Watcher kernel + bundle struct: `hls-processing-elements/mfpga/triangleCountDecoupled/memAccess.cpp`
- Host record mirror + readback: `TelemetryBundleHost` and `dumpTelemetry` in
  `software/triangleCountDecoupled/include/TriangleCountDecoupledDriver.h`
- PE→watcher tap wiring (drives the raw `TVALID`/`TREADY` taps + the uniform
  RegNext stage): `architecture-generator/src/main/scala/HardCilk.scala`
  (`connectWatcher`)
- Telemetry HBM region: reserved on `HBM[16:31]` (base `0x2_0000_0000`); compute
  uses `HBM[0:15]`.
