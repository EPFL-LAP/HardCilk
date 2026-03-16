# FCCM'26 — NetCilk Hardware Artifact

> Multi-FPGA graph analytics on Alveo U55C using HardCilk and VNx.

**Platform:** Ubuntu 24.04 · x86\_64 · Two Alveo U55C cards

---

## Table of Contents

1. [Hardware Setup](#1-hardware-setup)
2. [Software Prerequisites](#2-software-prerequisites)
3. [Building the Hardware](#3-building-the-hardware)
4. [Building the Host Driver](#4-building-the-host-driver)
5. [Running the Benchmarks](#5-running-the-benchmarks)

---

## 1. Hardware Setup

This artifact targets a host machine with **two Alveo U55C cards** on the same PCIe complex.
The two cards must be connected **directly via QSFP port 0** using a QSFP cable.
No network layer is instantiated — the cards communicate point-to-point.

---

## 2. Software Prerequisites

### 2.1 — HardCilk toolchain

Requires **Python 3.13**. Clone the repository and run the platform installer, which sets up all required tools and libraries into a self-contained environment:

```bash
git clone --recursive https://github.com/Mahfouz-z/hdlstuff.git
cd hdlstuff
python3 ubuntu-24.04-x86_64.py
```

Activate the environment before any of the steps below (and in every new shell session):

```bash
source ~/.local/opt/hdlstuff/bin/activate-hdlstuff.sh
```

### 2.2 — Alveo U55C platform packages

Install the following packages from the [AMD Alveo U55C download page](https://www.amd.com/en/support/downloads/alveo-downloads.html/accelerators/alveo/u55c.html#alveotabs-item-vitis-tab):

| Package | Install on |
|---|---|
| Xilinx Runtime (XRT) | Both servers |
| Deployment Target Platform | Server with the two FPGAs |
| Development Target Platform | Synthesis server |

### 2.3 — Vitis Core Development Kit 2024.1

Required for place-and-route of the generated designs.
Download from the [Xilinx Vitis 2024.1 download page](https://www.xilinx.com/support/download/index.html/content/xilinx/en/downloadNav/vitis/2024-1.html).

### 2.4 — CMAC license

The designs in this repo use the `CMAC_USPLUS` Xilinx IP, which requires a license.
Licenses are available **free of charge** from the [AMD CMAC UsPlus product page](https://www.amd.com/en/products/adaptive-socs-and-fpgas/intellectual-property/cmac_usplus.html).

---

## 3. Building the Hardware

All scripts live under `scripts/` in the HardCilk repo. Run them in order:

```bash
cd scripts
```

**Step 1 — Compile HLS kernels to Verilog:**
```bash
./build_benchmarks_hls.sh
```

**Step 2 — Generate the HardCilk circuit for each benchmark:**
```bash
./generate_benchmarks_hardcilk.sh
```

**Step 3 — Create the Vitis xclbin workspace for a specific benchmark:**
```bash
./generate_benchmark_xclbin_project.sh [pageRank | triangleCount | graphRandomWalk]
```

**Step 4 — Run place-and-route to produce the `.xclbin`**
(ensure Vitis 2024.1 and all prerequisites are on `PATH`):
```bash
cd xclbin-workspace/<benchmark>/
make all
```

---

## 4. Building the Host Driver

The driver source is placed inside the xclbin workspace by Step 3 above.

```bash
cd xclbin-workspace/<benchmark>/src/host
mkdir build && cd build
cmake ..
make -j
```

The compiled binary is written to:
```
build/projects/<benchmark>/<benchmark>_xrt
```

---

## 5. Running the Benchmarks

```
./<benchmark>_xrt <xclbin_path> <graph_file> <enable_vnx> [fpga_count]
```

| Argument | Required | Description |
|---|---|---|
| `xclbin_path` | ✓ | Path to the `.xclbin` bitstream |
| `graph_file` | ✓ | Input graph in edge-list format |
| `enable_vnx` | ✓ | `1` = bring up VNx Ethernet links · `0` = skip (single-FPGA runs) |
| `fpga_count` | optional | Number of FPGAs to use (default: `2`) |

**Examples:**

```bash
# Two FPGAs with VNx networking
./pageRank_xrt overlay.xclbin graph.edgelist 1

# Single FPGA, no networking
./pageRank_xrt overlay.xclbin graph.edgelist 0 1
```