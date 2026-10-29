# External fine-grained CPUs

CPU integration is opt-in. An existing architecture without these fields keeps
its native PE streams, closure grants, external write buffers, and memory fabric.

## JSON additions

- Task `fineGrainedCpu`: `{ "streamWidth": 64, "slabPool": "continuation_task" }`.
  CPUs use wrapper ABI 2: every task beat and MMIO register is 64 bits wide, so
  `streamWidth` must be 64 (the default) and divide the task width and every
  global destination's width. The former 8-, 16-, and 32-bit streams are
  rejected. `slabPool` is optional
  for CPUs which do not allocate. Set `peHDLPath` empty and disable
  `generateSpawnNextWriteBuffer` and `generateArgOutWriteBuffer`: the CPU implements
  its own write ordering. These CPUs are external RTL modules.
- Task `allocatorSlabBytes`: defaults to 0, preserving closure-sized grants. A
  positive power of two opts that continuation task's allocator into slab grants.
  CPU consumers of the pool must use the same slab size; legacy consumers cannot
  use it. The generated host driver allocates aligned, exclusive slabs anywhere in
  the 64-bit local address space and refills with fresh memory. Individual closure completion never frees
  a slab. Reclamation requires globally quiescing and resetting all consumers.
- CPU `floatingPoint`: defaults to false. When true, the CPU is emitted with
  its FP add/multiply/FMA unit and its firmware is built for `rv64if`.
- Task `memoryReader`: `{ "variableSize": false, "maxOutstanding": 32 }`
  makes the PEs of that task elastic-bugra's `HardCilkMemoryReader`, a
  task-independent decoupled read. It uses the CPU stream adapters: the last
  64-bit beat of a task is the address, the other beats are forwarded, and the
  read value replaces the address in a task for the reader's only `spawnList`
  destination, which must have the same width. With `variableSize`, address
  bits 63:62 give log2 of a 1/2/4/8-byte read. Readers have no continuations,
  arguments, firmware, or control port; a read error sets their `fault`.
- System `directMemoryPorts`: defaults to false. When true, export each memory
  master directly as `m_axi_NN`, preserving widths and IDs, for an external memory
  fabric or shared-memory simulator. This bypasses the board-specific HBM fabric.

Complete examples are `taskDescriptors/fibonacci_cpu.json` (two CPUs) and
`taskDescriptors/fibonacci_cpu_global.json` (three CPUs with cross-type spawning).
Several task types may spawn into one destination: their `taskInGlobal` ports
are numbered across all source types (previously every source type started at
0, which left ports undriven). CPU integration currently rejects multi-FPGA, variable-spawn notification, and
CPU malloc-input configurations.
Application code must use compatible task/continuation layouts and counter rules.

## Generated stream adapters

`CpuIntegration.PortAdapters` is called by the normal `HardCilk` generator. Each
CPU exports `BindTo_PE_<task>_<index>_<kind>` for its used streams:

- `taskIn`: split a native task into low-first stream chunks. There is no TLAST;
  the CPU's configured `TASK_BEATS` supplies the length.
- `taskOut`: assemble a fixed-size local task from AXIS chunks with TLAST on the
  final chunk and full TKEEP/TSTRB.
- `taskOutGlobal`: consume one whole header beat (no TLAST), then route and
  assemble the payload. IDs 1..N select `spawnList[task]` excluding self in list
  order. Header 0 is consumed internally by the CPU for local tasks. Each
  destination can have a different native task width.
- `sendArgument`: route a 64-bit counter address to `sendArgumentList[task]`.
  For N>1, its high ceil(log2(N)) bits are a zero-based route tag and are cleared
  before forwarding. For N=1 no tag is needed.
- `closureIn`: one 64-bit slab address per grant from the selected common pool.
  The CPU subdivides it according to its own closure-size table. This replaces
  the legacy per-continuation-type closure grant wiring for that CPU only.

Malformed framing, masks, or route IDs set sticky `cpuInterfaceFault`; the
faulting input stops until reset. Stream configurations are recorded in HDL
metadata. `CpuIntegration.Emit` additionally writes `cpu-interfaces.json` with
software routing IDs, payload sizes, task beats, and slab settings.

## Emission and validation

From `architecture-generator` with the project's Chext dependencies installed:

```sh
sbt -batch 'runMain CpuIntegration.Emit taskDescriptors/fibonacci_cpu.json output/cpu-fibonacci'
sbt -batch 'Test / runMain CpuIntegration.DescriptorCheck'
sbt -batch 'Test / runMain CpuIntegration.AdapterTestEmit output/cpu-adapters'
```

`CpuIntegration.Emit` emits the production `HardCilk` class, not a behavioral
scheduler. CPU RTL is separately emitted from the `elastic-bugra` repository to
avoid mixing its newer Chext dependency with this project's version.

In that CPU checkout, `docs/hardcilk-integration.md` documents the AXI/AXI-Lite CPU
wrapper, capability register map, boot helper, and firmware. Its
`integration/hardcilk/run.sh <this-HardCilk-checkout>` composes the two emitted RTL
files and simulates complete Fibonacci systems with randomized AXI timing.
Set `HARDCILK_ARCH=fibonacci_cpu_global` to exercise global task routing.
`integration/hardcilk/test-adapters.sh <this-HardCilk-checkout>` checks stream
adapters, descriptor validation, new/legacy host allocator behavior, and legacy
architecture emission. A board integration supplies the external AXI fabric and
management address decoding; the portable example exposes those bus ports.

## Generate an application project with `-k`

`-k` (including its existing `--aie-project` alias) now selects the CPU project
flow when the descriptor has `fineGrainedCpu` tasks. Architectures without CPU
tasks retain the original AIE/HLS output and filenames. The CPU project flow
requires all PEs to be CPUs and `directMemoryPorts=true`.

The source/application split follows the PageRank AIE/HLS experiment templates:
`cpu_templates/fibonacci/` owns architecture JSON, `src/cpu/` worker logic, and
`src/host/projects/fibonacci/` result checks. `cpu_project_template/` and
`SoftwareUtil.cpu.ProjectGenerator` own the reusable generated infrastructure.

```sh
sbt -batch 'runMain HardCilk.HardCilkEmitter -k -g --project-template cpu_templates/fibonacci --cpu-repo /path/to/elastic-bugra -o output/cpu-projects cpu_templates/fibonacci/fibonacci.json'
cd output/cpu-projects/fibonacci_hardcilk_output/software/cpu_fibonacci
make sim
```

The output contains generated firmware configurations, linker/startup files,
routing constants, host drivers and boot support, a composed RTL system, and
Make/CMake entry points. CPU RTL is emitted separately per task configuration;
PE counts determine the number of instances and boot records. `fineGrainedCpu`
can additionally set `numThreads`, `instructionMemoryWords`, and `taskQueueDepth`.
Closure sizes/type indices derive from `spawnNextList` in declaration order.

`-k` alone creates the project; its first build emits missing RTL. `-k -g` also
emits runtime RTL and places it in the project. `--project-template` is optional:
without it the generator creates empty application directories to fill in.
`--cpu-repo` can be omitted if `CPU_REPO` will be supplied at build time.
The native driver is generated under `src/host/driver/`; application code belongs
under `src/host/projects/`. Existing application files are preserved on
regeneration, with a notice if the template has changed. Use a fresh output
directory to import an updated template.

See [the generated project guide](cpu_project_template/README.md) for the complete
layout, build settings, ownership rules, and host simulation API. The generated
project supplies a portable AXI/AXI-Lite system and Verilator host build, not
board-specific interconnect or Vitis/XRT packaging.

Regression command: `scripts/test_cpu_projects.sh /path/to/elastic-bugra`.
The generated two-/three-CPU projects were simulated for Fibonacci 0, 1, 2, 5,
10, and 12 under seeds 1 and 42. A scaled four-CPU configuration with two
8-thread Fibonacci PEs and distinct per-type instruction-memory sizes also
passed. PageRank `real/pagerankv2` and
`real-hls/pagerankv2` templates from the experiment repository were also checked
with the existing `-k` flow.
