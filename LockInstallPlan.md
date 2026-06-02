# Bringing the LockChisel LockServer into HardCilk (BFS, single-FPGA, XRT)

## What we are building and why

BFS marks vertices in a shared `Visited` array. Multiple processing elements
(PEs) race to be the first to mark a vertex, so each "have I seen this vertex?"
must be an **atomic test-and-set** against one globally-consistent copy of the
bitmap. The `LockServer` from LockChisel is exactly that engine: PEs send it
lock/atomic requests over AXI-Stream, it serializes them against an on-chip tag
store, performs the read-modify-write against HBM through its own AXI master, and
streams back the previous value.

This document describes how to embed that engine **inside** the HardCilk
top-level kernel (not as a separate Vitis compute unit), wire its request/response
lanes to every BFS PE, give its HBM master a **dedicated HBM pseudo-channel**, and
push the whole thing through the **XRT (`v++`) flow** for both the `hw` and
`hw_emu` U55C platforms. It is strictly single-FPGA: **no CMAC, no inter-FPGA
networking.**

End-state topology:

```
        ┌────────────────────── HardCilk top kernel "BFS_0" (one Vitis CU) ──────────────────────┐
        │                                                                                          │
 taskIn▶│  Scheduler / Allocator / ArgNotifier ─▶ BFS PE                                           │
        │                                          helper×16 ─toLock─▶┐   ┌──fromLock──▶ helper×16   │
        │                                                             ▼   │                          │
        │   PE m_axi_gmem + server masters ──┐                 ┌──────────────┐                      │
        │                                    ▼                 │  LockServer  │                      │
        │                          ┌──────────────────┐        │ n=16 p=4     │                      │
        │                          │ HBM interconnect │        │ tagStore=128 │                      │
        │                          │ → m_axi_00..K     │        └──────┬───────┘                      │
        │                          └────────┬─────────┘    io.gmem (1 AXI master)                     │
        │                                   │                          │  m_axi_(K+1)  (dedicated)    │
        └────────────────────────────────────┼──────────────────────────┼────────────────────────────┘
                                            │                          │
                                  HBM[0:30] (shared, 31 ch)     HBM[31] (dedicated)
```

The configuration we target: **one shared LockServer**, `N=16` lanes (the 16
`sparse_edgemap_helper` PEs), `P=4` atomic-memory-unit lanes, `tagStoreSize=128`,
first bring-up at **200 MHz**. Only the helper PEs participate: they are the ones
that race to test-and-set the `Visited` bitmap. The root `BFS` PE marks only the
source vertex, and does so during init when it is the sole running PE, so it needs
no lock lane. One shared server (rather than one per helper) is required for
correctness: every helper tests-and-sets the *same* `Visited` bitmap, so they must
share one tag store and one HBM view of that buffer. The lane count must be a
multiple of `2*P` (the AMU bucketing constraint), which is why `N=16` helpers is
the chosen size rather than 15.

---

## How HardCilk assembles a system (orientation)

If you know LockChisel but not this repo, here is the mental model you need. Full
file/line references are in **Appendix A**.

**The generator.** `architecture-generator` is an sbt/Chisel project. Its entry
point `HardCilk.HardCilkEmitter` reads a **JSON descriptor** (one per benchmark,
under `taskDescriptors/`) describing the tasks, their PE counts, queue sizes, and
how tasks spawn/argument each other. It elaborates one big Chisel `Module`
(`HardCilk.HardCilk`), emits SystemVerilog, runs `sv2v` to plain Verilog, and
converts the reset to active-low. It also emits an `hdlinfo.json` (a machine
description of the top module's ports) and, with `-c`, a C++ header
(`FullSysGenDescriptor.h`) the host driver compiles against.

**PEs are black boxes parsed from Verilog.** You write/synthesize each PE in
Vitis HLS separately; the generator does **not** see HLS C++. Instead
`HLSHelpers.parseVitisModule` reads the PE's generated `.v` and infers its
interface by pattern-matching port names: any `*_TDATA` becomes an AXI-Stream
port (master if it's an `output`, slave if `input`), `m_axi_gmem_*` becomes an
AXI-MM master, and `ap_start/done/idle/ready` are detected. So **a PE gets a new
stream port simply by exposing a new `*_TDATA` in its Verilog** — no generator
change. This is exactly how the lock ports appear: the BFS HLS already emits
`toLock_*` (output → master AXIS) and `fromLock_*` (input → slave AXIS).

**The top module wiring.** `HardCilk.HardCilk` builds the subsystem modules
(PEs, schedulers, allocators, argument notifiers) from a "blueprint", connects
them according to a connection graph derived from the spawn/argument lists,
exports any unconnected PE-side ports as top-level IO, and then calls
`buildAndConnectHBM` to gather every AXI-MM master in the design, bin them into a
configurable number of groups, and expose each group as a top-level master named
`m_axi_00`, `m_axi_01`, … The number of these ports is `numHbmPortExports`. There
is also one AXI-Lite slave, `s_axil_mgmt_hardcilk`, through which the host
programs every subsystem's control registers.

**Multi-FPGA is a compile-time flag.** When the descriptor sets `mFPGASynth` or
`mFPGASimulation`, the top module additionally builds `m_axis_mFPGA` / `s_axis_mFPGA`
network ports and remote-access logic (`buildMfpgaConnections`). With both flags
off, none of that is elaborated. **CMAC itself is not in the generator at all** —
it is a separate `.xo` the XRT project links in and stream-connects to those
mFPGA ports. So "single-FPGA, no networking" means: flags off in the JSON, and a
CMAC-free XRT project.

**The XRT flow.** Each benchmark has a directory under `xrt-projects/<name>/`
with a `Makefile` and tcl scripts. `package_kernel.tcl` globs the generated
Verilog from `src/IP/*.v`, packages it as a `user_managed` RTL kernel paired with
a hand-written kernel description `src/xml/user_0.xml`, producing `<name>_0.xo`.
The `Makefile` then runs `v++ -l` with a connectivity config
`src/cfg/conn_*.cfg` (which maps each `m_axi_NN` port to HBM and, for the existing
benchmarks, stream-connects the CMAC kernel) to produce the `.xclbin`.
A staging script (`scripts/generate_benchmark_xclbin_project.sh`) copies the
generator output (`rtl/` → `src/IP/`, `software/` → `src/host/`) into a workspace.

**The host driver.** The host opens the kernel as an `xrt::ip` and programs the
management registers by **raw register writes** (`xrt::ip::write_register(addr,
value)`) at byte offsets, *not* via named `set_arg`. The offsets come from the
per-server base addresses baked into the generated `FullSysGenDescriptor.h`. HBM
buffers are plain `xrt::bo`s; the kernel addresses them by absolute device
address, which the host writes into the control registers / root task. This
matters later: the scalar arguments declared in `user_0.xml` are **packaging
bookkeeping only** — they are never read at runtime.

---

## The lock packet contract

The Chisel `LockServer`'s packet layout is the canonical wire format; the HLS PE
is rewritten to speak it. Both request and response are 136-bit AXI-Stream beats
(`ap_axiu<136>` on the HLS side, `AxiStream(136)` on the Chisel side).

**Request (`toLock`, PE → server):**

| bits      | field         | meaning                                                   |
|-----------|---------------|-----------------------------------------------------------|
| `63:0`    | `tag`         | byte address of the `Visited` slot to test-and-set        |
| `127:64`  | `data`        | operand (the store value; for plain test-and-set = 1)     |
| `131:128` | `operation`   | opcode (table below)                                      |
| `132`     | `isBlocking`  | 1 = server retries until it wins; 0 = one-shot            |
| `135:133` | reserved      | 0                                                         |

**Response (`fromLock`, server → PE):**

| bits      | field            | meaning                                              |
|-----------|------------------|------------------------------------------------------|
| `63:0`    | `success`/status | a forwarded RMW returns status = 1 here              |
| `127:64`  | `current`        | previous memory contents (the "was it visited?" bit) |
| `135:128` | reserved         | 0                                                    |

(The AMU response path packs `Cat(0.U(8), data, 1.U(64))`, i.e. status in the low
64 bits and the returned value in `[127:64]`. Plain lock/unlock responses use bit
0 = success.)

**Opcodes** (`lockchisel.Operation`): `Unlock=0`, `Lock=1`,
`LockSetUnlockAndReturnCurrent=2`, `LockSetIfGreater…=3`, `LockSetIfLess…=4`,
`LockAddOneReturnCurrent=5`, `UnlockNoResponse=7`.

BFS's `testAndSet(Visited, v)` — "set the byte to 1, return its previous value;
the vertex was already visited iff previous ≠ 0" — maps to opcode **2**
(`LockSetUnlockAndReturnCurrent`) with `data = 1` and `tag = &Visited[v]`. The
server's AMU performs the read-modify-write against HBM and returns the old byte
in `current`.

---

## Implementation

### 1. Rewrite the HLS lock packet and re-synthesize the PEs

The BFS HLS sources live in `hls-processing-elements/mfpga/BFS/` (`util.h` and
`BFS.cpp`, where `BFS.cpp` defines both the `BFS` top and the
`sparse_edgemap_helper` top). Reshape their lock payload to the 136-bit contract
above, and put the lock ports **only on the helper** — the helper PEs are the ones
that race to mark vertices, so they are the lock lanes. The root `BFS` PE marks
only the source vertex, during init when it is the sole running PE, so it has no
lock port at all.

- In `util.h`: define the lock payload as a 136-bit AXI-Stream type
  (`using lock_req = ap_axiu<136,0,0,0>;` and likewise `lock_resp`). Provide an
  opcode enum matching the contract (`enum LockOperation` with
  `LOCK_OP_SET_AND_RETURN_CURRENT = 0b0010`, …), a `make_lock_req(addr, value, op,
  blocking)` packer, and `lock_resp_success(resp)` (`tdata[63:0]`) /
  `lock_resp_current(resp)` (`tdata[127:64]`) decoders. `LockChisel/HLS/PE.cpp`
  (`make_lock_req` / `make_add1_req`) constructs this exact bit layout — use it as
  the reference. **Gotcha:** a C-style cast of the `LockOperation` enum straight to
  `ap_uint<4>` is an "ambiguous conversion" in Vitis HLS 2024.1; assign through a
  plain integer (`req.data(131,128) = (uint8_t)op;`).
- **Visited is 8-byte-strided.** The AMU does a single **8-byte, full-strobe** RMW
  per request (`size=3`, `fullStrobe` in `AtomicMemoryUnit.scala`), so a 4-byte
  `uint32_t` Visited slot would be corrupted (the write clobbers the neighbouring
  slot). Define `VISITED_SLOT_BYTES = 8` in `util.h`, have `visited_lock_addr` use
  `* VISITED_SLOT_BYTES`, and make every Visited read/write in `BFS.cpp`
  `uint64_t`. This propagates to the host (step 8: size/zero `Visited` at
  `vertex_count * 8` and lay `&Visited[v]` on an 8-byte stride). `distance` (int32)
  and `nextFChar` (uint32) keep their 4-byte width.
- In `BFS.cpp`, the **helper** issues the lock: `try_set_and_return_current` /
  `testAndSet` build a request with opcode 2 (`LOCK_OP_SET_AND_RETURN_CURRENT`),
  `tag = &Visited[v]`, `data = 1`, **non-blocking** (one-shot `write_nb`; a failed
  enqueue means "not the first visitor" and the helper moves on to the next
  neighbour). Decode `success`/`current` from the response. Keep the lock
  `#pragma HLS INTERFACE axis port=toLock/fromLock` lines on the helper only.
- In `BFS.cpp`, the **BFS top** takes no `toLock`/`fromLock` ports. Its `init`
  marks the source with a plain `MEM_ARR_OUT(... visited ... 1)` write — no lock
  request.
- Re-synthesize both tops with `scripts/build_hls_kernel/build_kernels.sh` (it
  stamps the HLS tcl and runs `vitis_hls`), re-exporting
  `hls-kernel-output/BFS/{BFS,sparse_edgemap_helper}/` at a 200 MHz clock. See
  Appendix B for the exact command and environment.
- Verify the helper exposes the contract and BFS exposes no lock ports:
  - `grep -hE "(input|output) +\[[0-9]+:0\] +(toLock|fromLock)_TDATA;" hls-kernel-output/BFS/sparse_edgemap_helper/*.v`
    → `output [135:0] toLock_TDATA;` and `input [135:0] fromLock_TDATA;`.
  - `grep -c "toLock\|fromLock" hls-kernel-output/BFS/BFS/BFS.v` → `0`.

After this, the generator's Verilog parser sees `toLock` (136-bit master AXIS) and
`fromLock` (136-bit slave AXIS) on the helper, matching `LockServer.io.req` /
`io.resp` exactly — no width adapters anywhere — and nothing lock-related on BFS.

### 2. Copy the LockServer Chisel into the generator

Copy these from `LockChisel/chisel/src/main/scala/lockchisel/` into
`architecture-generator/src/main/scala/lockchisel/`, keeping the `lockchisel`
package:

- `LockServer.scala`, `AtomicMemoryUnit.scala`, `AxiStream.scala`
- `Helpers/InputArbiter.scala`, `Helpers/AvailableSlotTracker.scala`

Do **not** copy `Main.scala` (its standalone emitter and Vitis kernel-wrapper
writer are irrelevant — we instantiate `LockServer` directly in Chisel) or
`tests/` (chiseltest-only).

The two projects already share the same dependency set (chisel 6.0.0,
`hdlstuff %% chext 0.1.1`, `hdlstuff %% hdlinfo 0.1.0`, scala 2.13.12), so no
`build.sbt` change is needed. If sbt can't resolve the `hdlstuff` artifacts, add
the `hdlstuff-local` ivy file-resolver block from `LockChisel/chisel/build.sbt`.
Confirm with `sbt compile` before wiring anything. The Chisel core itself is not
modified — the protocol adaptation lives entirely on the HLS side (step 1).

It is worth porting one of the LockServer chiseltests (adapted to drive the
contract above) to lock the request/response field mapping before spending time
in `hw_emu`.

### 3. Teach the descriptor about a lock config

**Files:** `architecture-generator/src/main/scala/Util/DescriptorsClean.scala`
(case classes; package `Descriptors`),
`architecture-generator/src/main/scala/Util/DescriptorsJSON.scala` (circe codecs),
`architecture-generator/taskDescriptors/mfpga/BFS.json`. (Ignore
`Util/Descriptors.scala` — it is the dead `DescriptorsOld` package.)

In `DescriptorsClean.scala`, add the case class next to the other small descriptor
case classes (e.g. near `SideConfig`, ~line 86):

```scala
case class LockConfig(N: Int, P: Int, tagStoreSize: Int)
```

Add an optional field to the `FullSysGenDescriptor` case class parameter list
(~line 179, alongside `mFPGASynth`, `widthAXIAddress`, etc.):

```scala
lockConfig: Option[LockConfig] = None,   // default keeps every existing JSON valid
```

Mark which tasks participate in locking with an **explicit descriptor field**
rather than inferring it from the PE's Verilog. Add a defaulted `Boolean` to the
`TaskDescriptor` case class (~line 125, alongside `hasAXI`):

```scala
participatesInLock: Boolean = false, // Whether this task's PEs get lock req/resp lanes
```

This is cleaner than the old "a PE participates iff it exposes a `toLock` AXIS
port" rule: participation is now declared in one place (the JSON), independent of
how the HLS happens to name its ports, and the generator can validate the lane
count up front (below) instead of discovering it while walking parsed modules.

In `FullSysGenDescriptor.validate()` (~line 458), add a guarded block:

```scala
// No task may opt into locking unless a lockConfig is present to serve it.
if (lockConfig.isEmpty) {
  val orphans = taskDescriptors.filter(_.participatesInLock).map(_.name)
  require(orphans.isEmpty,
    s"Tasks have participatesInLock=true but no top-level lockConfig is set: ${orphans.mkString(", ")}")
}

lockConfig.foreach { lc =>
  require(isPow2(lc.P), "lockConfig.P must be a power of two")
  require(lc.P <= lc.N, "lockConfig.P must be <= N")
  require(lc.tagStoreSize % lc.P == 0, "lockConfig.tagStoreSize must be a multiple of P")
  require(lc.N % (2 * lc.P) == 0, "lockConfig.N must be a multiple of 2*P (AMU bucketing)")
  // N must equal the total number of lock-participating PE lanes. A task opts in
  // via participatesInLock=true; each of its PEs gets one lane. For BFS only the
  // helper participates: 16 helper PEs = 16 lanes.
  val lockLanes = taskDescriptors.filter(_.participatesInLock).map(_.numProcessingElements).sum
  require(lockLanes == lc.N,
    s"lockConfig.N (${lc.N}) must equal the total PEs of tasks with participatesInLock=true ($lockLanes)")
  require(lockLanes > 0,
    "lockConfig is set but no task has participatesInLock=true")
}
```

In `DescriptorsJSON.scala`, add the codec next to the others (~line 38, beside
`sideConfigDecoder`):

```scala
implicit val lockConfigDecoder: Decoder[LockConfig] = deriveConfiguredDecoder[LockConfig]
implicit val lockConfigEncoder: Encoder[LockConfig] = deriveConfiguredEncoder[LockConfig]
```

(circe with `withDefaults` ignores unknown JSON keys, which is why BFS.json's
current per-task `LockConfig` blocks are silently dropped today. `withDefaults`
also means tasks without `participatesInLock` in the JSON default to `false`, so
every existing benchmark stays valid.)

A task "participates in locking" iff its descriptor sets
`participatesInLock: true`. This is declared in the JSON, not inferred from the
PE's `toLock` Verilog port, so step 4 just reads the field off the
`TaskDescriptor` rather than pattern-matching on the parsed `VitisModule`.

Then edit `taskDescriptors/mfpga/BFS.json` in place (BFS is single-FPGA only, so
there is no multi-FPGA variant to preserve):

- set `sparse_edgemap_helper.numProcessingElements` from `8` to `16` (the helper
  PEs are the lock lanes, and `N` must be a multiple of `2*P`);
- delete the two stray per-task `"LockConfig": {…}` blocks;
- add `"participatesInLock": true` to the `sparse_edgemap_helper` task entry only
  (the `BFS` task does not take a lock lane);
- add a top-level entry `"lockConfig": { "N": 16, "P": 4, "tagStoreSize": 128 }`;
- set `"mFPGASynth": false` and `"mFPGASimulation": false`;
- keep `maximumAXIPorts` consistent with the ≤31 PE/server channel budget (step 9).

### 4. Instantiate and wire the LockServer in the top module

**Files:** `architecture-generator/src/main/scala/HardCilk.scala` (lanes,
instantiate, endpoint wiring), `architecture-generator/src/main/scala/HBMInterconnect.scala`
(dedicated port helper).

Add the work as a new private method on `HardCilk` — e.g. `connectLockServer(peMap)`
— and call it from the orchestration block in the class body. Put the call
**after `buildAndConnectHBM(...)`** (`HardCilk.scala` ~line 113) so
`numHbmPortExports` is already set and you append the lock port after the regular
`m_axi_*` ports. Guard the whole thing:

```scala
fullSysGenDescriptor.lockConfig.foreach { lc => connectLockServer(lc, peMap) }
```

**Lane assignment.** Inside that method, walk the tasks with
`participatesInLock = true` in a deterministic order (the `taskDescriptors` order)
and assign each PE a global lane: the 16 helper PEs → lanes 0..15. `peMap` is
`Map[String, Seq[VitisWriteBufferModule]]`; iterate it in `taskDescriptors` order
so the mapping is stable. Assert the lane count equals `lc.N` (`validate()`
already guarantees this, so the assert is just a tripwire).

**Instantiate** (the Chisel core is unmodified; just construct it):

```scala
val lockServer = Module(new lockchisel.LockServer(
  n = lc.N, p = lc.P, tagStoreSize = lc.tagStoreSize, lockTraceCsv = false))
// idle defaults so any unused lane is safely tied off
for (i <- 0 until lc.N) {
  lockServer.io.req(i).valid := false.B
  lockServer.io.req(i).bits  := DontCare
  lockServer.io.resp(i).ready := false.B
}
```

**Connect the endpoints.** For each participating PE, fetch its `toLock`/`fromLock`
ports — they are `chext.amba.axi4s` ready/valid interfaces (the same type
`VitisWriteBufferModule` handles for `taskIn`/`taskOut`). The chext axi4s field
accessors are `TVALID` / `TREADY` / `TDATA` (see how `HardCilkMfpgaConnections.scala`
uses `.TVALID`, `.TREADY`, `.TDATA`). `lockServer.io.req(lane)` /
`.io.resp(lane)` are `Decoupled(AxiStream(136))` with fields
`valid`/`ready`/`bits.tdata`/`bits.tlast`. Map them explicitly:

```scala
val toLock   = pe.getPort("toLock").asInstanceOf[chext.amba.axi4s.Interface]
val fromLock = pe.getPort("fromLock").asInstanceOf[chext.amba.axi4s.Interface]
val req  = lockServer.io.req(lane)
val resp = lockServer.io.resp(lane)
// PE -> server
req.valid      := toLock.TVALID
toLock.TREADY  := req.ready
req.bits.tdata := toLock.TDATA
req.bits.tlast := true.B          // single-beat; or toLock.TLAST if the iface exposes it
// server -> PE
fromLock.TVALID := resp.valid
resp.ready      := fromLock.TREADY
fromLock.TDATA  := resp.bits.tdata
```

(Confirm whether the `onlyRV` axi4s config exposes `TLAST`/`TKEEP`; if it does,
drive/ignore them accordingly. The PE always sends single beats.)

This is the step most likely to go wrong. `VitisWriteBufferModule` forwards any PE
port it doesn't specially handle straight to its own `io` ("connect rest of ports",
`HLSHelpers.scala` ~line 289), and `HardCilk.exportMissingPEPorts` /
`HardCilkBuilder.connectSubsystems` will otherwise treat an unconnected PE port as
top-level IO to export. Because you consume `toLock`/`fromLock` here, they should
*not* appear as dangling IO — but the connection graph only knows about
taskIn/taskOut/argOut/etc., so these ports are simply never referenced there and
must be driven by your method. After generating, `grep -i lock <name>.v` on the top
module must show **no** `toLock`/`fromLock` ports — if it does, packaging fails.

**Give the lock master its dedicated HBM port.** `lockServer.io.gmem` is a single
AXI master. Do **not** add it to the master lists `buildAndConnectHBM` bins
(`interfacesPE` etc.). Instead export it exactly like the interconnect's
single-slave branch does — copy that branch in `HBMInterconnect.scala` (~lines
234-277) as a template:

```scala
// Simplest inline in HardCilk.connectLockServer: HardCilk extends HasHBMInterconnect,
// so cfgAxi4HBM / interfaceBuffer / axiOuts / numHbmPortExports are all in scope here.
val outputCfg = cfgAxi4HBM.copy(wId = 2)
val axiOut = IO(axi4.Master(outputCfg)).suggestName(f"m_axi_${numHbmPortExports}%02d")
val pc = Module(new axi4.full.components.ProtocolConverter(
  new axi4.full.components.ProtocolConverterConfig(
    axiSlaveCfg  = lockServer.io.gmem.cfg.copy(wUserAR=0, wUserR=0, wUserAW=0, wUserW=0, wUserB=0),
    axiMasterCfg = outputCfg)))
axi4.full.SlaveBuffer(AxiUserYanker(lockServer.io.gmem.asFull), axi4.BufferConfig.all(2)) :=> pc.s_axi
// AMU emits 64-bit beats; HBM port is 256-bit -> Widen
val widen = Module(new axi4.full.components.Widen(new axi4.full.components.WidenConfig(outputCfg)))
pc.m_axi :=> widen.s_axi
widen.m_axi :=> axiOut.asFull
interfaceBuffer.addOne(hdlinfo.Interface(axiOut.instanceName, hdlinfo.InterfaceRole.master,
  hdlinfo.InterfaceKind("axi4"), "clock", "reset", Map("config" -> hdlinfo.TypedObject(axiOut.cfg))))
axiOuts.addOne(axiOut)
numHbmPortExports += 1
```

Name it the next contiguous `m_axi_NN` (so the packaging tcl's `m_axi_*` glob and
the cfg `sp=` line pick it up with no special case). Incrementing
`numHbmPortExports` is what propagates the extra port into `hdlinfo.json`, the C++
header, and the generated `user_0.xml`.

The AMU addresses with a 64-bit `wAddr`; the HBM ports use `widthAXIAddress = 34`.
If the converter doesn't already narrow it, add an address truncation (or
`Util.AddressTransform`, used elsewhere in `HBMInterconnect`) so the lock master's
accesses land on the **same physical bytes** the PEs reach through their own
`m_axi_gmem` — there is exactly one `Visited` buffer and both must hit it.

We are intercepting in `HardCilk.scala` rather than extending the connection
graph, so `getSystemConnectionsDescriptor` and `getPhysicalPort` are untouched.

### 5. Keep it single-FPGA (no CMAC)

The generator side is handled by the JSON flags from step 3: with `mFPGASynth`
and `mFPGASimulation` false, `buildMfpgaConnections` is never called — no
`m_axis_mFPGA`/`s_axis_mFPGA` ports, no remote-stream logic, and the mFPGA terms
drop out of `getNumConfigPorts`. (Confirm BFS's continuation / argument-notifier
path elaborates and runs on this non-mFPGA route — the other benchmarks have only
ever been exercised with mFPGA on; see Risks.)

The XRT side (step 7) simply omits CMAC entirely: no `cmac_*.xo` build, no
`nk=cmac_*`, no `stream_connect=…mFPGA`, no post-sys-link overlay, and the two
`ipx::associate_bus_interfaces -busif {m,s}_axis_mFPGA` lines are dropped from
`package_kernel.tcl`.

### 6. Generate the kernel description and connectivity from Scala

`user_0.xml` is hand-maintained per benchmark today — there is no generator, and
its history is a trail of manual port-count corrections. Because the lock work
changes the port list (one extra master, mFPGA ports removed) and we want it to
stay in lockstep with the RTL, generate it instead. Everything it needs is already
computed in the generator: the `m_axi_*` list (`numHbmPortExports`, including the
lock port), the `s_axil_mgmt_hardcilk` register block (`getNumConfigPorts`, the
per-server base addresses `(idx << 6) + base`, register block size 6), and the
mFPGA on/off state.

Add a small emitter (e.g. `SoftwareUtil/KernelXmlTemplate.scala`, invoked from
`HardCilkEmitter`) that writes both files:

- `user_0.xml`: one `<port mode="master" dataWidth="256">` plus a `mem_N` arg per
  `m_axi_*` (the lock port being the last index); the `s_axil_mgmt_hardcilk`
  slave plus its scalar mgmt args (emit them at the true `(idx<<6)+base` block
  offsets — these are not read at runtime, so exact placement only needs to be
  self-consistent and within the slave's address range); **no** mFPGA ports;
  **nothing** for `toLock`/`fromLock` (they never cross the kernel boundary).
- `conn_u55c.cfg`: `nk=BFS_0:1:BFS_0`; every PE/server `m_axi_NN` mapped to the
  shared range `HBM[0:30]`; the lock port mapped to the dedicated `HBM[31]`; a
  single `[clock] freqHz=200000000:BFS_0.clock`. No CMAC, no stream connects, no
  `[advanced]` overlay.

This emitter is generic — the only lock-specific thing is "one extra `m_axi`,"
which it gets for free from `numHbmPortExports`. Build it as a general capability
but invoke it **only for BFS** for now, leaving triangleCount/pageRank/
graphRandomWalk on their existing hand-written XML so their builds need no
re-validation. Retrofitting them later is a drop-in.

Note the division of labor between the two port counts: `numHbmPortExports`
(total masters, including the lock port) drives the port list; the C++ header's
`getNumberAxiMasters()` returns `reduceAxi` (PE/server masters only, lock
excluded), which is what the host iterates — so the host never tries to drive the
lock port.

### 7. Create the BFS XRT project

Create `xrt-projects/BFS/`, modeled on `xrt-projects/triangleCount/` but
CMAC-free and single-clock:

- `Makefile`: start from triangleCount's, then delete the VNx-clone / CMAC build /
  post-sys-link machinery; set `KERNEL_OBJS := $(XO_DIR)/BFS_0.xo`; point the
  connectivity at the generated `conn_u55c.cfg`; call
  `gen_xo.tcl … BFS_0 $(TARGET) $(DEVICE) 0`; keep `$(TARGET)` parameterized for
  `hw`/`hw_emu` and add an `emconfig` target for emulation.
- `scripts/gen_xo.tcl`: copy verbatim (kernel name `BFS_0`).
- `scripts/package_kernel.tcl`: copy, drop the two mFPGA
  `associate_bus_interfaces` lines; keep the `m_axi_*` association loop (covers
  the lock port) and the `s_axil_mgmt_hardcilk` + clock/reset associations.
- `scripts/clear_drc_errors.tcl`: copy verbatim.
- `src/xml/user_0.xml`, `src/cfg/conn_u55c.cfg`: generated in step 6 and staged
  in (not hand-written).
- `src/IP/`, `src/host/`: staged from the generator output (step 8/9).

### 8. Host driver

Add a non-mFPGA application directory `software/BFS/` (the emitter's `-c -p` copies
`software_template/` and overlays `software/<name>/`; only `software/mfpga/*`
exist today). The host loads the xclbin, programs the management registers, seeds
the root BFS task, and validates results. The LockServer is internal and
free-running — the host never addresses it directly; it only has to place and
zero-initialize the `Visited` buffer.

`software/BFS/src/xrt_main.cpp`, modeled on
`software/mfpga/triangleCount/src/xrt_main.cpp` with the multi-FPGA controls
removed:

1. `xrt::device(0)`, `load_xclbin`.
2. Open the single user-managed CU `BFS_0` as an `xrt::ip`.
3. Allocate HBM `xrt::bo`s for the CSR graph, `dist_out`, the zero-initialized
   `Visited` bitmap, the ping-pong frontiers, `nextFChar`, and the continuation
   region. There is one `Visited` buffer; its address is what both the BFS task
   args and the lock tags (`&Visited[v]`) reference. Pinning the lock master to
   `HBM[31]` is bandwidth isolation only — the buffer is reachable from any
   channel. **`Visited` is 8-byte-strided** (step 1): size it at
   `vertex_count * 8` bytes, zero-initialize it, and lay out slot `v` at
   `visited_base + v*8` so it matches both the AMU's 8-byte RMW and the PE's
   `uint64_t` reads. (Only `Visited` changed width; `distance`/`nextFChar` stay
   4-byte.)
4. Program the management registers by **raw `write_register` at the offsets from
   `FullSysGenDescriptor.h`** (`schedulerServersBaseAddresses`, …) — not
   `set_arg`. Reuse the XRT backend in
   `software_template/driver/include/{hardCilkDriver.{h,tpp}, memIO_xrt.h}`
   (`XRTMemory::writeReg32/64` wrap `xrt::ip::write_register`).
5. Seed the root task / initial frontier, start the CU, and poll `done` / the
   scheduler registers. Add a wall-clock watchdog (the pattern in
   `LockChisel/host/host.cpp` is a good model) so a lock deadlock surfaces as a
   clear failure rather than a hang.
6. Sync results back and compare against the CPU golden.

Golden and graph input: reuse the CPU BFS at `/beta/bradley/BFS/BFS.cpp` and its
`Graph` loader. Share or copy its `Graph` class and reference `BFS` into
`software/BFS/include/` so the golden is literally the CPU implementation. The
host calls `G.load(<path>, false)` where **the path is a single easy-to-edit
line** (the CPU `main()` hard-codes `/beta/bradley/HardCilk/facebook_combined.txt`;
for `hw_emu` point it at a small graph), computes
`BFS<sparse_format>(G, 0, dist_out_golden)`, lays the same CSR + `Distance` /
`Visited` / frontier buffers into HBM, runs the kernel, and compares FPGA
`dist_out` element-by-element. `print_bfs_summary` from the CPU source is handy
for diagnostics. Add the per-benchmark driver subclass (buffer layout + register
map) modeled on `software/mfpga/triangleCount/include/*`.

No new management registers are needed for locking. (Optionally expose a
lock-enable / stats register, or surface the LockServer's `lockTraceCsv` printf
path, for debugging.)

### 9. Build scripts

- `scripts/generate_benchmarks_hardcilk.sh`: add a BFS line, e.g.
  `sbt "runMain HardCilk.HardCilkEmitter taskDescriptors/mfpga/BFS.json -o ../HardCilk-output/ -g -c -r <reduceAxi> -p"`.
  `reduceAxi` counts PE/server masters only (the lock port is added on top inside
  the generator), so keep it ≤ 31; start around 30. The PE `m_axi_gmem` masters
  (1 from BFS, 3 from each of the 16 helpers) plus the
  scheduler/allocator/argument-notifier server masters must bin into ≤ 31
  channels.
- `scripts/generate_benchmark_xclbin_project.sh`: add `BFS` to
  `VALID_BENCHMARKS`. The staging (copy `rtl/*` → `src/IP/`, `software/` →
  `src/host/`) is benchmark-agnostic; have the emitter or this script place the
  generated `user_0.xml` / `conn_u55c.cfg` into `xrt-projects/BFS/src/{xml,cfg}/`.
- HLS: `scripts/build_hls_kernel/build_kernels.sh` rebuilds `BFS` and
  `sparse_edgemap_helper` after the step-1 changes.

### 10. `hw` vs `hw_emu`

The RTL, `.xo`, and connectivity are identical for both; only the build/run
wrapper differs.

- `hw_emu`: `make … TARGET=hw_emu`, add `emconfigutil --platform $(PLATFORM)`,
  run with `XCL_EMULATION_MODE=hw_emu`, and an `xrt.ini` for logging. Use a tiny
  graph — the full pipeline plus the LockServer's HBM round-trips are slow in
  emulation, and the host watchdog is essential.
- `hw`: `make … TARGET=hw`; full implementation. Keep triangleCount's
  `--vivado.synth.jobs/impl.jobs` and `--linkhook` DRC-clearing flags. Targeting
  200 MHz with a 128-entry tag store (a size known to close at 300 MHz) leaves
  comfortable timing headroom.
- Part/platform are already U55C
  (`xcu55c-fsvh2892-2L-e` / `xilinx_u55c_gen3x16_xdma_3_202210_1`); set both the
  HLS clock and the cfg `[clock] freqHz` to 200 MHz.

---

## How To Run

This walks the BFS build end-to-end as a sequence of concrete commands, in the
order the artifacts depend on each other (HLS Verilog → generator RTL/headers/xml
→ packaged `.xo` → linked `.xclbin` → host). Each layer has its own toolchain and
its own way to throw away stale state — the **Cleaning** subsection at the bottom
is the part to read before re-running after any source change.

All paths are absolute because the three toolchains and the generator output live
in different trees (see Appendix B). The build numbers below map to the
implementation steps above.

### 0. One-time environment setup (three separate shells/toolchains)

These do **not** stack into one environment — source the one the current step
needs. `enable_xilinx_2024.1` only sets up Vivado, *not* `vitis_hls`, so HLS needs
the Vitis_HLS `settings64.sh` directly (see Appendix B).

```bash
# (A) HLS C-synthesis  — step 1
source /alpha/tools/Xilinx/Vitis_HLS/2024.1/settings64.sh     # → vitis_hls on PATH

# (B) Chisel generator — steps 2–6
source ~/.local/opt/hdlstuff/bin/activate-hdlstuff.sh         # → chext/hdlinfo ivy repo + sv2v

# (C) Vivado / v++ / XRT — steps 7, 10 (package, link, run)
source /alpha/tools/Xilinx/Vivado/2024.1/settings64.sh
source /opt/xilinx/xrt/setup.sh
```

### Step 1 — Synthesize the BFS PEs (HLS)

Run the super-script from `scripts/` and force the 200 MHz target (its default is
250 MHz). It writes the synthesized Verilog the generator black-boxes.

```bash
source /alpha/tools/Xilinx/Vitis_HLS/2024.1/settings64.sh
cd /beta/bradley/HardCilk/scripts
bash build_benchmarks_hls.sh -b BFS -f 200          # add -D to keep intermediates for debugging
```

Output lands in `hls-kernel-output/BFS/{BFS,sparse_edgemap_helper}/`. (To drive a
single kernel directly instead, use `build_hls_kernel/build_kernels.sh` as in
Appendix B.) Verify the lock contract crossed into RTL:

```bash
grep -hE "(input|output) +\[[0-9]+:0\] +(toLock|fromLock)_TDATA;" \
  /beta/bradley/HardCilk/hls-kernel-output/BFS/sparse_edgemap_helper/*.v
# → output [135:0] toLock_TDATA;   and   input [135:0] fromLock_TDATA;
grep -c "toLock\|fromLock" /beta/bradley/HardCilk/hls-kernel-output/BFS/BFS/BFS.v   # → 0
```

### Steps 2–6 — Generate RTL, C++ header, kernel.xml + connectivity (Chisel)

```bash
source ~/.local/opt/hdlstuff/bin/activate-hdlstuff.sh
cd /beta/bradley/HardCilk/architecture-generator
sbt compile                                          # sanity check after any Scala edit
```

Then emit BFS. Either run the whole batch:

```bash
cd /beta/bradley/HardCilk/scripts
bash generate_benchmarks_hardcilk.sh                 # emits BFS + the three existing benchmarks
```

…or just BFS (the line that script runs for it):

```bash
cd /beta/bradley/HardCilk/architecture-generator
sbt "runMain HardCilk.HardCilkEmitter taskDescriptors/mfpga/BFS.json -o ../HardCilk-output/ -g -c -r 30 -p"
```

`-r 30` keeps PE/server masters ≤ 31; the dedicated lock `m_axi` is appended on
top inside the generator. Output: `/beta/bradley/HardCilk-output/BFS_hardcilk_output/`
with `rtl/` (the top `.v` + PE IP), `software/` (the host project), and the
generated `user_0.xml` / `conn_u55c.cfg` placed into
`xrt-projects/BFS/src/{xml,cfg}/`. Inspect the top module before going further:

```bash
grep -i lock /beta/bradley/HardCilk-output/BFS_hardcilk_output/rtl/*.v   # → no toLock/fromLock ports
grep -c "m_axi_" /beta/bradley/HardCilk-output/BFS_hardcilk_output/rtl/hdlinfo.json   # exactly one more than -r
```

### Step 7 — Stage, package, and link the xclbin (XRT)

Stage the generator output into a build workspace, then build. Staging copies
`rtl/* → src/IP/`, `software/ → src/host/`, and the project's `Makefile`/tcl/xml/cfg:

```bash
cd /beta/bradley/HardCilk/scripts
bash generate_benchmark_xclbin_project.sh BFS        # → xclbin-workspace/BFS/

source /alpha/tools/Xilinx/Vivado/2024.1/settings64.sh
source /opt/xilinx/xrt/setup.sh
cd /beta/bradley/HardCilk/xclbin-workspace/BFS
make xo                                              # package BFS_0.xo (Vivado RTL kernel)
make TARGET=hw_emu                                   # link → build_dir.hw_emu.*/BFS.xclbin
# (or `make TARGET=hw` for the on-card bitstream — see step 10)
```

### Step 8 / Step 10 — Build and run the host

`hw_emu` and `hw` share the same `.xclbin` build invocation above (only `TARGET`
differs); the difference is the run wrapper. The host is a CMake project under the
staged `src/host/`; the BFS executable target is `BFS_xrt`.

```bash
source /opt/xilinx/xrt/setup.sh
cd /beta/bradley/HardCilk/xclbin-workspace/BFS/src/host
cmake -B build -S .                                  # HC_ENABLE_XRT_TARGETS defaults ON
cmake --build build -j                               # → build/.../BFS_xrt
```

Run against the linked xclbin. For emulation you also need an `emconfig.json` and
the `XCL_EMULATION_MODE` env var, and a **tiny** graph (point the host's graph-path
line at a small file — the full pipeline plus LockServer HBM round-trips are slow
in emulation):

```bash
# hw_emu
cd /beta/bradley/HardCilk/xclbin-workspace/BFS
make emconfig TARGET=hw_emu                          # writes emconfig.json into build_dir.hw_emu.*
cd build_dir.hw_emu.xilinx_u55c_gen3x16_xdma_3_202210_1
XCL_EMULATION_MODE=hw_emu /path/to/BFS_xrt BFS.xclbin   # watchdog surfaces a lock deadlock as a failure

# hw (on card)
/path/to/BFS_xrt /beta/bradley/HardCilk/xclbin-workspace/BFS/build_dir.hw.*/BFS.xclbin
```

### Cleaning stale build artifacts (do this before re-running after a change)

The layers cache aggressively and the staging step does **not** delete removed
files, so a partial rebuild can silently link stale RTL/host. Clean the layer(s)
downstream of whatever you changed:

| Changed… | Clean before rebuilding |
|----------|-------------------------|
| `util.h` / `BFS.cpp` (HLS) | `rm -rf /beta/bradley/HardCilk/scripts/hls_projects` then re-run step 1. The output `hls-kernel-output/BFS/` is overwritten in place; delete it too if a kernel was renamed/removed. |
| Any `.scala` in the generator | `cd architecture-generator && sbt clean` (drops stale compiled classes) before `sbt compile`/`runMain`. |
| The descriptor or generator logic (RTL output) | `rm -rf /beta/bradley/HardCilk-output/BFS_hardcilk_output` before re-emitting, so no stale `rtl/`, header, or xml/cfg survives. |
| Anything upstream of the workspace | **`rm -rf /beta/bradley/HardCilk/xclbin-workspace/BFS` before re-staging** — `generate_benchmark_xclbin_project.sh` uses `rsync -a`/`cp` with no `--delete`, so old `src/IP/*.v`, host sources, or a stale `user_0.xml`/`conn_u55c.cfg` would otherwise linger and get linked. |
| Only the v++ link / want a fresh xclbin | `cd xclbin-workspace/BFS && make clean` (drops `build_dir.*`/`_x_temp.*`/logs) or `make cleanall` (also drops `BFS_0.xo`, `reports/`, `.Xil`, `.ipcache`, packaged-kernel temps, `emconfig.json`). |
| Host source only | `rm -rf xclbin-workspace/BFS/src/host/build` then re-run cmake. |

Rule of thumb: a source change invalidates everything downstream of it in the
HLS → generator → stage → v++ → host chain, and only `make clean`/`cleanall` and the
explicit `rm -rf` of the output/workspace dirs are guaranteed to remove stale
products — incremental re-runs of the staging script will not.

---

## Build & verify order

1. **HLS protocol change** (step 1): rewrite `util.h`/`BFS.cpp`, re-synthesize,
   confirm 136-bit `toLock`/`fromLock` in the Verilog.
2. **Copy the LockServer Chisel** (step 2): `sbt compile`, no wiring.
3. **Descriptor** (step 3): add `lockConfig` parsing, edit `BFS.json`. Confirm the
   other benchmarks still generate byte-identically.
4. **Wire the top module** (step 4): lanes → instantiate → connect endpoints →
   dedicated `m_axi` port. Generate BFS RTL and inspect the top `.v` +
   `hdlinfo.json`: no dangling lock ports, exactly one extra `m_axi_*`.
5. **Kernel.xml / cfg emitter** (step 6): generate `user_0.xml` + `conn_u55c.cfg`;
   diff the port list against the generated `.v`.
6. **XRT project** (step 7): Makefile + tcl, stage the generated files, package
   `BFS_0.xo`.
7. **`hw_emu`** (steps 8, 10): write the host, small graph, run, debug the
   handshake (watch for lock deadlock), validate against the CPU golden.
8. **`hw`** (step 10): build at 200 MHz, close timing, validate on card.

---

## Files touched

**Add (generator):**
- `architecture-generator/src/main/scala/lockchisel/{LockServer,AtomicMemoryUnit,AxiStream}.scala`
- `architecture-generator/src/main/scala/lockchisel/Helpers/{InputArbiter,AvailableSlotTracker}.scala`
- `architecture-generator/src/main/scala/SoftwareUtil/KernelXmlTemplate.scala` (kernel.xml + conn cfg emitter)

**Edit (generator):**
- `HardCilk.scala` — lane mapping, instantiate + wire LockServer, dedicated gmem export, lock-enable guard.
- `HBMInterconnect.scala` — export `lockServer.io.gmem` as its own `m_axi_NN`; increment `numHbmPortExports`.
- `Util/DescriptorsClean.scala` — `LockConfig` + `lockConfig` field + `participatesInLock` task field + validation.
- `Util/DescriptorsJSON.scala` — `LockConfig` circe codec.
- `HardCilkEmitter.scala` — invoke the kernel.xml/cfg emitter for BFS.
- `build.sbt` — only if sbt can't resolve `hdlstuff` (add the `hdlstuff-local` resolver).

**Edit (HLS / descriptor / scripts):**
- `hls-processing-elements/mfpga/BFS/{util.h,BFS.cpp}` — 136-bit lock packet, opcode 2.
- `architecture-generator/taskDescriptors/mfpga/BFS.json` — helper=16, mFPGA off, top-level `lockConfig`, `participatesInLock: true` on the helper task only.
- `scripts/generate_benchmarks_hardcilk.sh` — add the BFS emit line (`-r ≤ 31`).
- `scripts/generate_benchmark_xclbin_project.sh` — add `BFS` to the valid list.

**Add (XRT project):**
- `xrt-projects/BFS/Makefile` (CMAC-free, hw + hw_emu).
- `xrt-projects/BFS/scripts/{gen_xo,package_kernel,clear_drc_errors}.tcl` (package_kernel drops mFPGA associations).
- `xrt-projects/BFS/src/xml/user_0.xml` and `src/cfg/conn_u55c.cfg` (generated, staged in).

**Add (host):**
- `software/BFS/src/xrt_main.cpp`, `software/BFS/include/{graph.h,BFSDriver.h,testBench.h}`.

**Re-run:** HLS for `BFS` and `sparse_edgemap_helper`.

---

## Risks / watch-items

- **Dangling lock ports** — the top failure mode. If the endpoint wiring misses,
  `toLock`/`fromLock` leak to top-level IO and the kernel won't package or link.
  Check the generated `.v` after step 4.
- **`numHbmPortExports` off-by-one** — the extra lock port must propagate to the
  C++ header and `user_0.xml`, or host port/arg indexing shifts. Bump it in one
  place (`HBMInterconnect`) and let everything read from there.
- **Visited-buffer addressing** — the AMU's 64-bit address path versus the
  34-bit HBM port: the truncation/transform on the lock master must land on the
  same bytes the PEs read. Validate on a tiny graph where the visited set is
  hand-checkable.
- **mFPGA-off regressions** — turning the flags off changes `getNumConfigPorts`
  and removes the argument-notifier network ports. The existing benchmarks were
  only run with mFPGA on, so confirm BFS's `isCont`/argument-notifier path
  elaborates and runs without them.
- **HLS field/opcode drift** — after editing `util.h`, recheck the packed bit
  ranges against the contract (opcode at `[131:128] = 2`, `data` at `[127:64] = 1`,
  response `current` at `[127:64]`).

---

## Appendix A — HardCilk repo map (for a fresh reader / AI)

Concrete locations and behaviors gathered while planning this, so the work can be
resumed without re-exploring. Paths are relative to the `HardCilk/` repo root;
line numbers are approximate and may drift.

### Generator entry & RTL emission
- `architecture-generator/src/main/scala/HardCilkEmitter.scala` — `object
  HardCilkEmitter extends App`. Parses args (`Util/ArgParser.scala`), reads the
  JSON via `Descriptors.DescriptorJSON.parseJsonFile`, calls `validate()`, then
  `Util.HardCilkEmitterUtil.generateRTL`. With `-p` (`project_sc_generation`) it
  also copies `software_template/`, renames `project_template` → `<jsonName>`,
  generates C++ headers, and overlays app sources from `software/<name>/` (or
  `software/mfpga/<name>/` when mFPGA is on).
- `Util/HardCilkEmitterUtil.scala` — `generateRTL`: copies each task's
  `peHDLPath` files and `src/main/resources/*` into the output, instantiates
  `new HardCilk(...)` via `ChiselStage.emitSystemVerilogFile`, runs `sv2v` on
  `<name>.sv` → `<name>.v`, then `VerilogResetConverter.convertToActivelow`
  (rewrites `input reset` → `input reset_n; wire reset = ~reset_n`). Returns
  `numHbmPortExports`.
- `Util/ArgParser.scala` — flags: `-r/--reduce-axi` (default 32) → `reduce_axi`;
  `-g` rtl, `-c` cpp headers, `-p` sc project, `-d` debug, `--addr-transform`.

### Descriptors
- `Util/DescriptorsClean.scala` (package `Descriptors`, **active**) — `LockConfig`
  goes here. `FullSysGenDescriptor` (~line 179) holds `taskDescriptors`,
  `spawnList`, `spawnNextList`, `sendArgumentList`, `mallocList`, `mFPGASynth`,
  `mFPGASimulation`, `widthAXIAddress=34`, etc. Constructor assigns per-server
  management base addresses: `base = if (isVitisProject) 0x10 else 0x0`, each
  server gets `(idx << 6) + base` (lines ~205-247). `getNumConfigPorts()`
  (~line 384) sums scheduler/allocator/memAllocator/spawner servers plus mFPGA
  extras. `getSystemConnectionsDescriptor()` (~line 297) derives the AXIS
  connection graph from the spawn/argument lists. `validate()` (~line 455).
- `Util/DescriptorsJSON.scala` — circe codecs via `deriveConfiguredDecoder` with
  `Configuration.default.withDefaults`; **unknown JSON keys are silently
  ignored** (which is why the current per-task `LockConfig` in BFS.json is dropped
  today). Add the `LockConfig` codec here.
- `Util/Descriptors.scala` — package `DescriptorsOld`, **dead**, do not edit.

### PE black-boxing
- `HLSHelpers/HLSHelpers.scala` — `parseVitisModule` (~line 408) reads
  `${peHDLPath}/${name}.v`, extracts `m_axi_gmem_*` widths to build an AXI-MM
  master, finds every `<dir> [hi:lo] <name>_TDATA;` (regex ~line 507) to build
  AXIS interfaces (master if the line is `output`, slave if `input`,
  `axi4s.Config(wData, onlyRV=true)`), and detects `ap_start/done/idle/ready`.
  `VitisModule` (~line 67) is the `BlackBox`; `VitisWriteBufferModule` (~line 114)
  wraps it, adds spawnNext/argOut write buffers, and **connects every remaining PE
  port straight to its own `io`** (~line 289) — this is the path lock ports would
  fall through if not intercepted. `getPort(name)` looks up a port by name.
  `VitisModuleFactory.apply` returns `Seq.fill(numProcessingElements)(Module(new
  VitisWriteBufferModule(...)))`.

### Top module & HBM
- `HardCilk.scala` — `class HardCilk extends Module with HasHBMInterconnect with
  HardCilkHasMfpgaSupport`. Orchestration (~lines 96-121): `builder.defineBlueprint`
  → build `peMap`/`schedulerMap`/`allocatorMap`/`notifierMap`/… → `connectManagement`
  (AXI-Lite demux) → `connectPEs` (ties `ap_clk/ap_rst_n/ap_start`) →
  `builder.connectSubsystems` → `exportMissingPEPorts` (exports unconnected PE
  ports as top IO — keep lock ports out of this) → `connectGlobalSignals` →
  `buildAndConnectHBM` → `buildMfpgaConnections()` (only if mFPGA) →
  `exportPEControl` → `generateHdlInfo`. `instantiateManagementDemux` (~line 173)
  builds the `s_axil_mgmt_hardcilk` slave + a `Demux` with `registerBlockSize = 6`,
  decoding `addr >> 6`.
- `HardCilkBuilder.scala` — `defineBlueprint` (factories for every subsystem;
  PEs only for tasks with a non-empty `peHDLPath`) and `connectSubsystems` (wires
  per the connection graph; exports PE ports for tasks whose PE doesn't exist).
- `HBMInterconnect.scala` — `trait HasHBMInterconnect`, `buildAndConnectHBM`
  (~line 47). Collects masters into `interfacesPE` (PE `m_axi_gmem`,
  `m_axi_spawnNext`, `m_axi_argOut`, write buffers) and server interface lists,
  bins them into `reduceAxi` groups (math ~lines 122-144), and for each non-empty
  group emits a top master `m_axi_${i}%02d` (single-slave path uses
  `AxiUserYanker`→`ProtocolConverter`→optional `Widen`; multi-slave path adds a
  `Mux`). The single-slave path (~lines 234-277) is the template for the dedicated
  lock port. `numHbmPortExports` (~line 162) = count of non-empty groups. The HBM
  master config is `cfgAxi4HBM` (`wData=256`, `wId=2`). An XDMA-slave block exists
  but is disabled (`if (false)`, ~line 147).
- `HardCilkMfpgaConnections.scala` — `trait HardCilkHasMfpgaSupport`,
  `buildMfpgaConnections` (~line 63), entirely gated on `mFPGASynth ||
  mFPGASimulation`; builds `m_axis_mFPGA`/`s_axis_mFPGA` (512-bit) and remote
  routing. Off ⇒ none of this elaborates.

### LockServer (from LockChisel)
- `LockChisel/chisel/src/main/scala/lockchisel/LockServer.scala` — `class
  LockServer(n, p, tagStoreSize, lockTraceCsv)`. IO: `req = Vec(n,
  Flipped(Decoupled(AxiStream(136))))`, `resp = Vec(n, Decoupled(AxiStream(136)))`,
  `gmem = axi4.Master(...)` (one master, `wId = log2Ceil(n/p)+log2Ceil(p)`,
  `wAddr=64`, `wData=64`, read+write; internally `p` `AtomicMemoryUnit`s muxed via
  `chext`'s `axi4.full.components.Mux`). `object LockServer { ReqWidth=136;
  RespWidth=136 }`. Request decode reads `tag = tdata(63,0)`, `data = tdata(127,64)`,
  `operation = decode(tdata(131,128))`, `isBlocking = tdata(132)`. AMU response
  packs `Cat(0.U(8), data, 1.U(64))`.
- `AtomicMemoryUnit.scala` — `require(n % (2*p) == 0)`; per-slot FSM doing the
  HBM read-modify-write; AXI id = table slot index.
- Constraints (from `Main.scala` checkConfig): `p` power-of-two, `p ≤ n`,
  `tagStoreSize % p == 0`; plus the AMU's `n % (2*p) == 0`.

### XRT projects
- `xrt-projects/triangleCount/` is the reference. `Makefile`: builds `cmac_<if>.xo`
  from the external `xup_vitis_network_example` repo and links it via
  `conn_u55c_if0.cfg` — **all CMAC machinery is what we strip for BFS.**
  `scripts/gen_xo.tcl` calls `package_kernel.tcl` then `package_xo … -kernel_xml
  ./src/xml/user_${index}.xml`. `scripts/package_kernel.tcl` globs
  `./src/IP/*.v|*.sv`, packages as a `user_managed` RTL kernel, associates every
  `m_axi_*` (plus the mFPGA streams and `s_axil_mgmt_hardcilk`) with `clock`.
- `src/xml/user_0.xml` — hand-written kernel.xml: `m_axi_NN` master ports
  (`dataWidth=256`), `s_axil_mgmt_hardcilk` slave, the mFPGA streams; args are
  the scalar mgmt regs (addressQualifier 0, on `s_axil_mgmt_hardcilk`), one
  `mem_N` `void*` per master, and the stream args. **The scalar arg offsets are
  cosmetic** (the driver writes raw registers; see below).
- `src/cfg/conn_u55c_if0.cfg` — `[connectivity]` (`nk=`, `sp=… :HBM[...]`,
  `stream_connect=…mFPGA…cmac`), `[clock]`, `[advanced]` post-sys-link overlay.
- `scripts/generate_benchmark_xclbin_project.sh` — stages `HardCilk-output/
  <name>_hardcilk_output/{rtl→src/IP, software→src/host}` into
  `xclbin-workspace/<name>/`. Has a hard-coded `VALID_BENCHMARKS` list.

### Host / driver
- `software_template/driver/include/memIO_xrt.h` — `struct XRTMemory`. `writeReg32/
  64` and `readReg32/64` call `xrt::ip::write_register/read_register` at **raw byte
  offsets**. `allocateMemFPGA` carves `xrt::bo`s per HBM bank. So management
  programming is offset-based, driven by `FullSysGenDescriptor.h`, **not**
  `set_arg`.
- `SoftwareUtil/CPPHeaderTemplate.scala` — emits `FullSysGenDescriptor.h`:
  per-task `mgmtBaseAddresses` (scheduler/allocator/memAllocator base address
  vectors), `getNumberAxiMasters()` returning `reduceAxi`,
  `getNumberPEsAXISlaves()`. The host indexes masters/registers from this header.
- `software/mfpga/triangleCount/{src/xrt_main.cpp, include/*}` — closest existing
  host to model BFS on (strip multi-FPGA).
- `LockChisel/host/host.cpp` — simple XRT host with a wall-clock watchdog; good
  pattern for deadlock detection.

### BFS specifics
- `hls-processing-elements/mfpga/BFS/{BFS.cpp,util.h}` — HLS sources (both PE
  tops). `util.h` defines `lock_req`/`lock_resp` as `ap_axiu<136,0,0,0>`, the
  `enum LockOperation` opcode set (`LOCK_OP_SET_AND_RETURN_CURRENT = 0b0010`, …),
  the `make_lock_req` / `lock_resp_success` / `lock_resp_current` helpers,
  `VISITED_SLOT_BYTES = 8`, and the `BFS_args` / `sparse_edgemap_helper_args`
  structs (buffer layout). The lock is on the **helper only**:
  `sparse_edgemap_helper`'s `testAndSet` / `try_set_and_return_current` issue
  opcode-2 requests with `tag = &Visited[v]`, `data = 1`; the `BFS` top has no
  lock ports and marks the source vertex with a plain write.
- `hls-kernel-output/BFS/{BFS,sparse_edgemap_helper}/*.v` — the synthesized PE
  Verilog the generator black-boxes. `sparse_edgemap_helper.v` exposes
  `toLock_TDATA[135:0]` (output) and `fromLock_TDATA[135:0]` (input); `BFS.v` has
  no lock ports.
- `taskDescriptors/mfpga/BFS.json` — two tasks: `BFS` (root, `isCont`, taskId 0,
  1 PE) spawns `sparse_edgemap_helper` (taskId 1, 16 PEs, `participatesInLock`);
  the helper sends an argument back to BFS. `mFPGASynth`/`mFPGASimulation` are off
  and a top-level `lockConfig` selects the shared LockServer.
- `/beta/bradley/BFS/BFS.cpp` — the CPU reference: `Graph::load(path, bool)`,
  `BFS<sparse_format>(G, source, dist_out)`, `print_bfs_summary`; `main()`
  hard-codes the graph path. This is the golden.

---

## Appendix B — Environment & tooling (so a fresh you can jump straight to code)

Everything below was verified on this machine on 2026-06-01. Paths are absolute
because several of these tools live **outside** the HardCilk repo.

### Repo geography (mind the siblings)

- **HardCilk repo:** `/beta/bradley/HardCilk` (this repo; primary working dir).
- **LockChisel:** `/beta/bradley/LockChisel` — a **sibling**, *not* a subdirectory
  of HardCilk. Every `LockChisel/...` path in this plan means
  `/beta/bradley/LockChisel/...`. Key contents:
  - `chisel/src/main/scala/lockchisel/{LockServer,AtomicMemoryUnit,AxiStream,Main}.scala`,
    `chisel/src/main/scala/lockchisel/Helpers/{InputArbiter,AvailableSlotTracker}.scala`,
    `chisel/src/main/scala/lockchisel/tests/` — the sources step 2 copies in.
  - `HLS/PE.cpp` — the reference packet builder (`make_lock_req` / `make_add1_req`)
    that step 1's `util.h` was modeled on.
  - `host/host.cpp` — the XRT host with the wall-clock watchdog (step 8 model).
- **CPU BFS golden:** `/beta/bradley/BFS/BFS.cpp` (also a sibling).
- **HardCilk generator output** lands in `/beta/bradley/HardCilk-output/`
  (note: sibling of the repo, `-o ../HardCilk-output/` from `architecture-generator/`).

### Activating the Xilinx toolchain (Vitis HLS / Vivado / XRT, 2024.1)

`~/.bashrc` defines shell **functions** (not aliases) `enable_xilinx_2024.1`, etc.
But `enable_xilinx_2024.1` only sources **Vivado** settings — it does **not** put
`vitis_hls` on `PATH`. For HLS you must source the Vitis_HLS settings directly:

```bash
# HLS C-synthesis (what step 1 / step 9 HLS rebuilds need):
source /alpha/tools/Xilinx/Vitis_HLS/2024.1/settings64.sh   # → vitis_hls on PATH
# Vivado / v++ / XRT (xclbin build, host link — steps 6/7/10):
source /alpha/tools/Xilinx/Vivado/2024.1/settings64.sh
source /opt/xilinx/xrt/setup.sh
```

- `vitis_hls` binary: `/alpha/tools/Xilinx/Vitis_HLS/2024.1/bin/vitis_hls`
  (it prints a harmless "vitis_hls executable is deprecated" warning — ignore;
  synthesis still completes).
- `XILINX_ROOT=/alpha/tools/Xilinx/`. Other versions exist via
  `enable_xilinx_{2022.1,2022.2,2025.1}` if ever needed.

### Re-synthesizing the BFS PEs (step 1 / step 9 HLS)

The driver is `scripts/build_hls_kernel/build_kernels.sh` (stamps the
`scripts/build_hls_kernel/hls_kernel.tcl` template per kernel and runs
`vitis_hls -f run_hls.tcl`). **Run it from `scripts/`** (it creates
`scripts/hls_projects/<kernel>/` and deletes it after, unless `-D`):

```bash
cd /beta/bradley/HardCilk/scripts
source /alpha/tools/Xilinx/Vitis_HLS/2024.1/settings64.sh
bash build_hls_kernel/build_kernels.sh \
  -d /beta/bradley/HardCilk/hls-processing-elements/mfpga/BFS \
  -f 200 \
  -p xcu55c-fsvh2892-2L-e \
  -o /beta/bradley/HardCilk/hls-kernel-output/BFS \
  -k BFS sparse_edgemap_helper        # -k must be LAST; lists all tops
```

- `-f 200` = 200 MHz (period 5.0000 ns). `-p` = U55C part `xcu55c-fsvh2892-2L-e`.
- `-d` is the source dir (all `*.cpp`/`*.h` in it, maxdepth 1, are added).
- Output: synthesized Verilog copied to `<-o>/<kernel>/` — i.e.
  `hls-kernel-output/BFS/{BFS,sparse_edgemap_helper}/`. The top module is
  `<kernel>.v`.
- Add `-D` to keep intermediates (project tree, logs, reports) under
  `scripts/hls_projects/<kernel>/` for debugging; the C-synth report is at
  `…/<kernel>_proj/solution1/syn/report/<kernel>_csynth.rpt`.
- It exits non-zero and prints a BUILD SUMMARY on any kernel failure; the failing
  kernel's log is `scripts/hls_projects/<kernel>/vitis_hls.log`.
- **Verify (step 1's check):**
  `grep -hE "(input|output) +\[[0-9]+:0\] +(toLock|fromLock)_TDATA;" hls-kernel-output/BFS/*/*.v`
  → expect `output [135:0] toLock_TDATA;` and `input [135:0] fromLock_TDATA;` on both.

### Building the Chisel generator (steps 2–6: `sbt compile` / `runMain`)

The `architecture-generator` is an sbt project that depends on the local
`hdlstuff` artifacts (`hdlstuff %% chext 0.1.1`, `hdlstuff %% hdlinfo 0.1.0`).
Activate that environment first, then use sbt normally:

```bash
source ~/.local/opt/hdlstuff/bin/activate-hdlstuff.sh    # → hdlstuff ivy repo (+ sv2v) on PATH
cd /beta/bradley/HardCilk/architecture-generator
sbt compile                                              # step 2 sanity check
# Emit one benchmark's RTL + C++ header + SC project (step 9 form):
sbt "runMain HardCilk.HardCilkEmitter taskDescriptors/mfpga/BFS.json -o ../HardCilk-output/ -g -c -r <reduceAxi> -p"
```

- `~/.local/opt/hdlstuff/bin/activate-hdlstuff.sh` →
  `/beta/bradley/hdlstuff/prefix/ubuntu/bin/activate-hdlstuff.sh`. Without it sbt
  can't resolve `chext`/`hdlinfo` (this is the fallback the plan's step 2 mentions;
  in practice this activate script is the fix, not a `build.sbt` resolver edit).
- Generation also shells out to **`sv2v`** (SystemVerilog → Verilog) — the activate
  script puts it on `PATH` too.
- `scripts/generate_benchmarks_hardcilk.sh` `cd`s into `architecture-generator`
  and runs the `sbt "runMain …"` lines (one per benchmark). Existing ones use
  `-r 22/22/23`; add the BFS line per step 9 (`-r ≤ 31`).
- Emitter flags (`Util/ArgParser.scala`): `-g` RTL, `-c` C++ headers, `-p` SC
  project, `-r/--reduce-axi` AXI grouping (default 32), `-d` debug.

### Target part / platform (all FPGA steps)

- Part: `xcu55c-fsvh2892-2L-e`. XRT platform:
  `xilinx_u55c_gen3x16_xdma_3_202210_1`. Clock for first bring-up: **200 MHz**
  (set both the HLS `-f` and the cfg `[clock] freqHz=200000000`).
