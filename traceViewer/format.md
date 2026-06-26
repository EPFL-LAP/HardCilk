# HardCilk telemetry trace format

This document specifies the on-disk/on-HBM format produced by the **watcher**
telemetry kernel (`hls-processing-elements/mfpga/triangleCountDecoupled/memAccess.cpp`)
for the `triangleCountDecoupled` benchmark. It is self-contained: a fresh implementer
should be able to write a useful viewer from this document alone.

The viewer needs to show, over time:
- when each PE is **ACTIVE** (consuming a task),
- when each PE is **WAITING** (idle, no incoming task),
- when each PE is **STALLED** (a task is available but it cannot accept it),
- per-HBM-port **read and write bandwidth** (bytes/cycle, hence bytes/s).

---

## 0. File header: HBM-port descriptor

Every `.bin` **begins with a self-describing header** carrying the generator's
HBM-port → module map (`<design>.hbmports.json`), so the viewer can label each
bandwidth port and PE by what is actually attached to it (which PE / scheduler /
allocator). The host (`TriangleCountDecoupledDriver.h`) finds the descriptor via
`$TCD_HBM_DESCRIPTOR`, else `triangleCountDecoupled.hbmports.json` in the cwd, else
`../triangleCountDecoupled.hbmports.json` (one level up, for runs launched from the
build folder), and writes the header before the beats.

> **Missing-descriptor fallback (misconfiguration, not a mode).** If the host
> cannot find the descriptor it prints a loud warning and writes a **headerless**
> file (beats at offset 0). A viewer must still tolerate this — no magic ⇒
> `beats_offset = 0` — but the trace is then unlabeled and should be treated as a
> setup error to fix, not a supported format.

Detect the header by the 8-byte magic at offset 0:

```
offset  0 : magic       = "HCKTRACE" (8 ASCII bytes)
offset  8 : u32 version = 1   (little-endian)
offset 12 : u32 flags         (little-endian; see below)
offset 16 : u64 json_length   (bytes of the JSON descriptor, excluding padding)
offset 24 : u64 beats_offset  (byte offset where telemetry beats begin; 32-aligned)
offset 32 : JSON descriptor   (json_length bytes, UTF-8)
   ...    : zero padding up to beats_offset
beats_offset : the 256-bit beat stream (everything in §1 onward)
```

### `flags` (offset 12, u32)

A little-endian bitfield describing how the trace was produced. Unused bits are 0
and reserved; a viewer should mask the bit it cares about and ignore the rest.

| bit | name           | meaning                                                   |
|----:|----------------|-----------------------------------------------------------|
| 0   | `is_emulation` | `1` = the run was a Vitis **emulation** (`hw_emu`/`sw_emu`); `0` = **real hardware** |

The host sets bit 0 from `XCL_EMULATION_MODE` (set during emulation, unset on HW).
Use it to label the trace's provenance — e.g. emulation traces are cosim-deterministic
and use a smaller telemetry reserve, while HW traces reflect real per-stack bandwidth
and timing. (This field was `reserved` in earlier traces, where it was always 0 —
which correctly reads as "real hardware", so old HW traces remain valid.)

If the magic is **not** present, `beats_offset = 0`. A viewer must check the magic
first and start beat decoding at `beats_offset`.

### The descriptor JSON

```json
{
  "design": "triangleCountDecoupled",
  "numComputePorts": 28,
  "pes": [
    { "peNumber": 0, "task": "whileLoopMain_reentry0_cont0", "statusPrefix": "cont0_status",    "indexInTask": 0 },
    { "peNumber": 4, "task": "memReader",                     "statusPrefix": "memReader_status","indexInTask": 0 },
    { "peNumber": 8, "task": "whileLoopMain_reentry0",        "statusPrefix": "reentry0_status", "indexInTask": 0 }
  ],
  "ports": [
    { "port": 4,  "portName": "m_axi_04",
      "masters": [ {"owner": "pe:memReader:0#main",   "role": "main",   "peNumber": 4, "wData": 32, "wId": 1} ] },
    { "port": 12, "portName": "m_axi_12",
      "masters": [ {"owner": "pe:memReader:0#argOut", "role": "argOut", "peNumber": 4, "wData": 32, "wId": 0} ] },
    { "port": 25, "portName": "m_axi_25",
      "masters": [ {"owner": "scheduler:memReader:vss:0", "role": "ring", "peNumber": null, "wData": 256, "wId": 1} ] }
  ]
}
```

A memReader PE owns **two** ports — `role: "main"` (its `m_axi_gmem` graph-read port)
and `role: "argOut"` (its argument/continuation write-buffer port) — both with the
same `peNumber`. The `role` tells you which is which without parsing the owner.

The descriptor answers two questions:

1. **What type is each STATUS `PE#`?** → `pes[]`. The STATUS bundle (§3) numbers PEs
   `0..11` in the watcher's monitored order, `peCount` per task, consecutively
   (`cont0` 0–3, `memReader` 4–7, `reentry0` 8–11). `pes[]` is exactly that table:
   `peNumber` → `task` / `statusPrefix` / `indexInTask`. Use it to label the PE rows.
2. **Which `PE#` owns a given memory port?** → each `ports[].masters[].peNumber`.
   So you can line a port's bandwidth up with that PE's ACTIVE/STALLED timeline.

Field notes:
- `port` is the **compacted exported index** — identical to the `BW_READ`/`BW_WRITE`
  port index in §4 and to the watcher's bandwidth tap `axiOuts(p)`.
- `role` is the master's function: `main` = the kernel's `m_axi_gmem` compute port;
  `argOut` / `argDataOut` / `spawnNext` = argument/continuation write-buffer ports;
  `ring` / `spawner` = scheduler ports. Use it directly to label a port (e.g. a
  memReader PE's `main` read vs its `argOut` write). When a single `peNumber` owns
  several ports, the `owner` also gets a `#<role>` suffix (e.g. `pe:memReader:0#main`).
- `owner` is `"<kind>:<task>:<index>[#<role>]"`. Per-PE kinds (`pe`, `spawnNextWB`,
  `sendArgumentWB`) get a non-null `peNumber`; shared servers (`scheduler` with
  `:vss:`/`:spawner:`, `closureAllocator`, `memoryAllocator`, `argumentNotifier`)
  have `peNumber: null` since they serve all PEs, not one. `xdma_or_external` marks
  a non-compute master (e.g. the XDMA slave).
- The watcher's **own** telemetry write ports are not listed (they are the trace
  sink, not a measured compute port).

This is the authoritative, per-build replacement for the hand-maintained port
identity table in §4 — read `ports[]` rather than assuming fixed widths.

---

## 1. Physical layout

The watcher is a free-running hardware block that writes fixed-size records to HBM,
into the telemetry region that starts at device address **`0x2_0000_0000`** (HBM
bank 16). The host reserves this region and reads it back into a `.bin` file after
the run. The viewer consumes that `.bin`.

- The stream is a flat sequence of **256-bit (32-byte) beats**, tightly packed,
  starting at byte offset 0 of the dumped region.
- The number of valid beats = `write_idx` (how many the watcher emitted). The host
  dump may contain trailing zero/stale beats beyond that; see §6 (Reading procedure).

### Beat = two 128-bit bundles

Each 32-byte beat holds **two independent 128-bit "bundles"**, little-endian:

```
byte  0 ..15  : slot0  (bundle, bits [127:0]   of the beat)
byte 16 ..31  : slot1  (bundle, bits [255:128] of the beat)
```

Read each bundle as a little-endian 128-bit unsigned integer. The two slots are
**independent** — decode each on its own. A beat may carry any mix of bundle types,
including one real bundle + one `NULL` (padding).

---

## 2. Bundle = 128-bit bit-packed, tagged union

Every bundle is bit-packed (NOT byte-per-field). The low byte is the type tag:

```
bits [7:0]  = header (bundle type)
```

| header | type      | meaning                                              |
|-------:|-----------|------------------------------------------------------|
| 0      | `NULL`    | empty padding slot; payload ignored — **skip it**    |
| 1      | `STATUS`  | a PE-handshake snapshot at a single cycle            |
| 2      | `BW_READ` sub 0  | per-port avg **read** bytes/cycle, ports 0..14   |
| 3      | `BW_READ` sub 1  | ports 15..29                                      |
| 4      | `BW_READ` sub 2  | ports 30..44  (only port 30 used)                |
| 5      | `BW_WRITE` sub 0 | per-port avg **write** bytes/cycle, ports 0..14  |
| 6      | `BW_WRITE` sub 1 | ports 15..29                                      |
| 7      | `BW_WRITE` sub 2 | ports 30..44                                      |
| 8      | `BW_ADDR` | a rotating per-port address sample (see §5)          |

All other header values are reserved; a viewer should skip unknown headers.

---

## 3. `STATUS` bundle (header = 1)

A snapshot of every monitored PE's input/output queue handshake at one cycle.

```
bits [7:0]    = 1
bits [55:8]   = 48 status bits  (12 PEs x 4 bits)
bits [127:56] = cycle_count     (72-bit unsigned)
```

### cycle_count
Free-running cycle counter **relative to the start of compute** (it begins at 0 when
the first task is dispatched by the scheduler — see §7). Multiply by the clock period
to get wall-clock time. At 100 MHz, 1 cycle = 10 ns.

### The 48 status bits
12 monitored PEs, 4 bits each. PE `k` occupies status bits `[k*4 +: 4]`, i.e. bundle
bits `[8 + k*4 +: 4]`. Within a PE's 4 bits (LSB first):

```
bit 0 : in_valid   (input queue: a task is being presented to the PE)
bit 1 : in_ready   (input queue: the PE can accept a task this cycle)
bit 2 : out_valid  (output queue: the PE is presenting a result)
bit 3 : out_ready  (output queue: downstream can accept the result)
```

PE index → name (current `triangleCountDecoupled` design, 4 PEs per task):

| k    | task / role                          |
|------|--------------------------------------|
| 0..3 | `whileLoopMain_reentry0_cont0[0..3]` (continuation PEs) |
| 4..7 | `memReader[0..3]`                    (graph memory readers) |
| 8..11| `whileLoopMain_reentry0[0..3]`       (re-entry PEs) |

### Deriving the three viewer states (from the **input** handshake)
For each PE, per STATUS sample:

```
WAITING  : in_valid == 0                  (no incoming task)
STALLED  : in_valid == 1 && in_ready == 0 (task available, PE can't take it)
ACTIVE   : in_valid == 1 && in_ready == 1 (task consumed this cycle)
```

Also useful: a **task consumed** event = `in_valid && in_ready`; a **result pushed**
event = `out_valid && out_ready`.

### Timing model
STATUS bundles are **edge-triggered**: the watcher emits one only when the 48-bit
status vector **changes**. So a STATUS sample at `cycle_count = T` means "this state
held from T until the next STATUS sample's cycle_count". Render each PE's state as a
piecewise-constant timeline: hold the state from one sample's cycle to the next.
(The very first state change after the start gate is consumed silently — see §7 — so
the timeline effectively begins at the first emitted STATUS sample.)

---

## 4. `BW_READ` / `BW_WRITE` bundles (headers 2–7)

Per-HBM-port average bandwidth over the most recent **128-cycle window**. Emitted once
per window (every 128 cycles). 31 ports are covered across 3 sub-bundles of 15 ports
each; read and write are separate bundles.

> **Timing a window.** `BW_READ`/`BW_WRITE` bundles carry no timestamp of their own.
> Each window also emits one `BW_ADDR` bundle (§5) whose `bits[124:53]` hold the
> window's final `cycle_count`; that is the time anchor for the whole set (the averages
> cover cycles `[cycle-127 .. cycle]`). Idle windows are skipped, so anchor by reading
> that field — not by counting emitted sets.

```
bits [7:0]              = header (2/3/4 = read sub 0/1/2 ; 5/6/7 = write sub 0/1/2)
bits [8 + slot*8 +: 8]  = port (sub*15 + slot) average, for slot = 0..14  (8-bit unsigned)
```

- `sub = header - 2` for reads, `header - 5` for writes; port index = `sub*15 + slot`,
  `slot = 0..14`.
- Each value is the **average bytes transferred per cycle** on that port over the
  window: it is `(total bytes in the 128-cycle window) >> 7`, saturated to 255.
  - **bytes/s = value × clock_frequency** (e.g. value × 100e6 at 100 MHz).
  - Reads are counted at the address phase as `(ARLEN+1) << ARSIZE` (the exact bus
    bytes of the burst); writes are counted at the data phase as `popcount(WSTRB)`
    (exact bytes written). A single-byte access counts as 1 byte, not a full beat.
- Ports are the exported HBM masters `m_axi_00 .. m_axi_27` (28 compute ports today;
  indices 28..30 are reserved and read 0). Port byte-width varies by port, but the
  value is already in **bytes**, so no per-port width conversion is needed.

Port identity: **use the embedded descriptor (§0), not a fixed table** — the exact
PE/scheduler attached to each port is per-build and emitted by the generator into
`ports[]` (keyed by the same port index used here). For reference, a typical
`triangleCountDecoupled` build has `m_axi_00..15` 32-bit, `m_axi_16..24` 512-bit
(the graph `memReader` data ports — these dominate read bandwidth), `m_axi_25..26`
256-bit, `m_axi_27` 64-bit, but always defer to the descriptor.

---

## 5. `BW_ADDR` bundle (header = 8)

A rotating per-port address sample **plus the window's timestamp**. Exactly one
`BW_ADDR` bundle is emitted per window, alongside that window's `BW_READ`/`BW_WRITE`
set, so it doubles as the **time anchor** for the whole set (see §4). The port index
advances each window so all address ports are covered over time.

```
bits [7:0]    = 8
bits [12:8]   = port index (0..30)
bits [32:13]  = AW address bits [39:20] (most-recent write address on that port)
bits [52:33]  = AR address bits [39:20] (most-recent read  address on that port)
bits [124:53] = cycle_count (72-bit) of the window's FINAL cycle  (see below)
bits [127:125]= reserved (0)
```

### Window timestamp (`bits [124:53]`)
This is the `cycle_count` (same clock/epoch as the STATUS timestamp, §3 — relative to
the start gate) of the **last cycle of the 128-cycle window** that the accompanying
`BW_READ`/`BW_WRITE` averages summarize. So a value `C` means the averages cover cycles
**`[C-127 .. C]`** inclusive, and windows land on `C = 128, 256, 384, …`.

Because every emitted window carries its own absolute cycle, **do not infer window time
by counting** — a window with zero traffic on all ports emits nothing (it is skipped),
so the N-th emitted BW set is not necessarily the N-th 128-cycle window. Read the cycle
from this field directly; it is robust to skipped-idle-window and dropped bundles.

The address bits remain **reserved for future address-range classification** (e.g. graph
vs scheduler); a basic viewer can ignore them but should still read the timestamp.

The 20 captured bits are AXI address bits **`addr[39:20]`** — i.e. the address with the
low 20 bits dropped (1 MB granularity). Reconstruct an approximate byte address as
`captured << 20`. These bits (not the top `addr[63:44]`, which are always zero because
HBM lives at `~0x1_0000_0000`) are what actually distinguishes regions (e.g. graph at
`0x1_0000_0000` vs scheduler at `0x1_2000_0000`). Note these are the addresses **as seen
at the exported port**, which may be permuted by the HBM address-transform stage —
interpret as opaque region tags for now.

---

## 6. Reading procedure

```python
import struct, json

def decode(path, clock_hz=100_000_000):
    data = open(path, "rb").read()
    # §0: optional self-describing header carrying the HBM-port descriptor.
    descriptor, beats_off, is_emulation = None, 0, False
    if data[:8] == b"HCKTRACE":
        flags      = int.from_bytes(data[12:16], "little")
        is_emulation = bool(flags & 0x1)                # bit0: 1=emulation, 0=hw
        json_len   = int.from_bytes(data[16:24], "little")
        beats_off  = int.from_bytes(data[24:32], "little")
        descriptor = json.loads(data[32:32+json_len])   # ports[] -> owners
    for off in range(beats_off, len(data) - 31, 32):
        slot0 = int.from_bytes(data[off:off+16],    "little")
        slot1 = int.from_bytes(data[off+16:off+32], "little")
        for bundle in (slot0, slot1):
            header = bundle & 0xFF
            if header == 0:                      # NULL padding, payload ignored
                continue
            elif header == 1:                    # STATUS
                status = (bundle >> 8)  & ((1 << 48) - 1)
                cycle  = (bundle >> 56) & ((1 << 72) - 1)
                pes = [(status >> (k*4)) & 0xF for k in range(12)]  # 4 bits each
                # bit0=in_valid bit1=in_ready bit2=out_valid bit3=out_ready
            elif 2 <= header <= 7:               # BW_READ / BW_WRITE
                is_write = header >= 5
                sub = header - (5 if is_write else 2)
                for slot in range(15):
                    avg  = (bundle >> (8 + slot*8)) & 0xFF       # bytes/cycle
                    port = sub*15 + slot
                    # bytes_per_sec = avg * clock_hz
            elif header == 8:                    # BW_ADDR + window timestamp
                port  = (bundle >> 8)  & 0x1F
                aw    = (bundle >> 13) & ((1 << 20) - 1)
                ar    = (bundle >> 33) & ((1 << 20) - 1)
                cycle = (bundle >> 53) & ((1 << 72) - 1)  # window's FINAL cycle;
                # the BW_READ/BW_WRITE set in this window covers cycles [cycle-127 .. cycle]
```

### Finding the valid region
**If the FPGA was reset (`xrt-smi reset` / reprogram) before the run** — the normal
workflow — then `write_idx` is 0 and the trace **starts at byte offset 0**; just read
beats until the first all-zero beat. This is the common case.

`write_idx` only resets on FPGA reset, not between host runs that share one
programming. So if you run **without** resetting, runs are appended back-to-back (no
clean gap) and the dump may begin with stale beats from earlier runs. As a safety net,
the host decoder scans for the first non-zero beat and dumps the contiguous populated
run from there; the reference host decoder (`TriangleCountDecoupledDriver.h`) is the
source of truth. Within a run, the start gate guarantees the first STATUS beat carries
a small `cycle_count` (it starts at 0 at compute start, not the ~hundreds-of-millions
offset of FPGA-programming time), so a small monotonically-increasing `cycle_count` is
a clean marker of a run's beginning.

---

## 7. Semantics / gotchas

- **Start gate.** The watcher stays completely idle after reset and writes nothing
  until the spawn scheduler dispatches its first task. From that instant `cycle_count`
  and the bandwidth windows start counting from 0, so timestamps are relative to
  compute start, not to FPGA programming (which can be hundreds of millions of cycles
  earlier). The gate latches and only re-arms on FPGA reset; it does **not** reset
  `write_idx` between host runs (see §6).
- **`write_idx` / offset 0.** When the start gate opens (first task dispatch after an
  FPGA reset), the watcher explicitly zeroes `write_idx` (the beat write pointer),
  `cycle_count`, the window counter and the bandwidth accumulators. So after an FPGA
  reset the trace deterministically starts at **byte offset 0**. The gate latches, so
  repeated host runs that share one FPGA programming keep the gate open and **append**
  (no per-run reset) — `xrt-smi reset` before a run is what gives a fresh offset-0
  trace.
- **Prime skip.** The very first beat the watcher would emit is consumed silently (a
  one-time hardware-pipeline prime). At most one STATUS sample is lost, right at the
  start.
- **Beat packing.** Each cycle the watcher emits at most one 256-bit beat. A STATUS
  bundle (emitted on a status change) takes slot0; bandwidth bundles fill the remaining
  slot(s). So a beat is `{STATUS, BW}`, `{BW, BW}`, `{STATUS, NULL}`, or `{BW, NULL}`.
  Bandwidth bundles for a window are guaranteed to be flushed within a few beats of the
  window boundary (well inside the next 128-cycle window).
- **Bundle order is the stream order.** Do not assume the 7 bandwidth bundles of a
  window are contiguous or in a fixed slot; each bundle is fully self-describing via its
  header. Reassemble per-port read/write vectors by header (read sub 0/1/2, write sub
  0/1/2). A complete window's bandwidth = the 6 BW bundles with headers 2..7 that appear
  together (interleaved with at most a handful of STATUS bundles).
- **Clock.** The current HW build runs at **100 MHz**. Use the actual build clock to
  convert cycles → time and bandwidth averages → bytes/s.

---

## 8. Quick reference

```
Header : magic "HCKTRACE", [8:12) u32 version, [12:16) u32 flags (bit0=is_emulation),
         [16:24) u64 json_length, [24:32) u64 beats_offset, then JSON, pad, beats.
Beat   : 32 bytes = slot0 (bytes 0..15), slot1 (bytes 16..31), each a 128-bit bundle.
Bundle : [7:0] header.
  1 STATUS : [55:8] 48 status bits (PE k -> [8+k*4 +:4] = in_v,in_r,out_v,out_r),
             [127:56] cycle_count (72b, since start gate).
  2..4 BW_READ  sub 0..2 : [8+slot*8 +:8] avg read  bytes/cycle, port = sub*15+slot.
  5..7 BW_WRITE sub 0..2 : [8+slot*8 +:8] avg write bytes/cycle, port = sub*15+slot.
  8 BW_ADDR : [12:8] port, [32:13] AW addr[39:20], [52:33] AR addr[39:20] (<<20 = byte addr),
              [124:53] cycle_count of window's FINAL cycle (set covers [cycle-127 .. cycle]).
  0 NULL   : skip.
PE state (input handshake): WAITING=!in_valid, STALLED=in_valid&!in_ready, ACTIVE=in_valid&in_ready.
bytes/s = bw_value * clock_hz.  window = 128 cycles.
```
