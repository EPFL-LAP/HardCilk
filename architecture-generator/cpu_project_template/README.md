# Generated HardCilk CPU project

Application sources belong in a reusable template; this directory is generated
with `HardCilk.HardCilkEmitter -k`. For CPU descriptors, `-k` selects this layout.
The legacy AIE/HLS project flow is unchanged.

```
architecture.json                    resolved architecture and server addresses
project.json                         task/PE manifest and default checkout paths
config/cpu/<task>.json                CPU emitter configuration, one per task type
config/cpu/<task>.ld                  generated instruction-image linker scripts
src/cpu/<task>/                       APPLICATION: firmware implementing worker()
src/cpu/generated/<task>/             generated routing/type constants
src/host/projects/<application>/      APPLICATION: src/*.cpp and optional include/
src/host/generated/include/           generated descriptor and simulator bindings
src/host/runtime/                     generated AXI host and shared-memory simulator
src/host/driver/                      generated native HardCilk host driver
src/rtl/<architecture>/               generated HardCilk RTL and interface metadata
src/rtl/cpu/<task>/                   separately emitted CPU RTL
src/rtl/system/                       composed System.sv and external ports.json
src/rtl/common/                       shared memory primitives
scripts/                             generated build and composition utilities
build/                               firmware, executables, logs (disposable)
```

## Build

Run `make sim` to compile the RV64 firmware, emit any missing RTL, build the host
application with Verilator, and run it with randomized AXI seeds 1 and 42.
`make firmware`, `make rtl`, and `make host` run individual build stages;
`make` defaults to host. `make clean` removes only `build/`. The equivalent host
entry point is `cmake -S src/host -B build/cmake && cmake --build build/cmake`.

Set `CPU_REPO` to the CPU checkout if it was not supplied with `--cpu-repo`.
`HARDCILK_REPO` and `CPU_REPO` override the defaults in `project.json`, including
when the project is moved. Previously emitted RTL and copied support files can
be rebuilt/simulated without the original checkouts. `make rtl` forces fresh
elaboration after RTL-source changes; other targets reuse matching emitted
configuration. Configuration/topology changes belong in the source architecture
JSON: regenerate the project to refresh the manifests and headers together.

Tools: sbt/Java and each repository's Chext dependencies, Python 3, Verilator,
C++17, and a RISC-V GCC toolchain. Optional overrides: `HDLSTUFF_PREFIX`, `SBT`,
`RISCV_CC`, `RISCV_OBJCOPY`, `VERILATOR`, `JOBS`, `APP`, and `SEEDS`. The default
HDL prefix is `$HOME/.local/opt/hdlstuff`; `/opt/riscv/bin` is also searched.
With multiple host applications, select one with `APP=<directory-name>`.

## Application contract

Each task type has its own firmware image, reused by every PE of that type.
Provide `void worker(void)` (`extern "C"` in C++) in `src/cpu/<task>/`.
The generated startup uses private per-thread scratchpad stacks. Firmware must
not depend on initialized data, BSS, or read-only data in instruction SRAM; the
linker rejects those sections. The default build uses RV64I without libc.

Include `hardcilk_task.h` for task length, local/global route IDs, argument route
IDs/tag size, and closure allocation type indices. Closure type indices follow
`spawnNextList[task]` order; allocation sizes are the corresponding task widths
in bytes. All grant consumers use the pool's configured slab size. The JSON's
`fineGrainedCpu` accepts `numThreads` (default 4), `instructionMemoryWords`
(default 1024), and `taskQueueDepth` (default 64).

A host simulation can include `simulation.hpp`, use `CpuSimulation` to allocate
shared memory, initialize scheduler queues and slab grants, load all images,
start the runtime, and wait for completion. The application supplies the root
task bytes, result layout, and correctness checks. `initialize` requires paused
servers and `reset` starts a fresh memory/ownership epoch. The simulator grants
32 exclusive slabs per allocator by default; increase that argument for larger
workloads. It does not automatically recycle/refill slabs. Physical host code
can use the generated `FullSysGenDescriptor.h` and `hardcilk_boot.hpp`; the
simulation host executable is built against Verilator.

Only all-CPU architectures with `directMemoryPorts=true` are supported by this
project flow. The generated RTL exposes separate AXI memory masters and AXI-Lite
controls, with an active-high reset. Board-specific memory interconnect, control
address decoding, clock/reset adaptation, and Vitis/XRT packaging remain external;
this project does not claim to produce an xclbin. `ports.json` records the actual
RTL boundary for that integration.

## Template ownership and regeneration

`--project-template <directory>` imports only `src/cpu`, `src/host/projects`,
`tests`, and `assets`. Other files in the template, including the source JSON,
remain source inputs. HardCilk regenerates infrastructure, never replaces an
existing application file, and reports when a supplied template differs from
it. Use a fresh `-o` output directory to refresh application sources from an
updated template. Do not keep lasting edits only in generated infrastructure.
