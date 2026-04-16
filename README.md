# FCCM'26 (NetCilk) — Simulation Artifact

## Platform Support

Ubuntu 24.04 (x86_64)

---

## Getting Started

### Prerequisites

- Python 3.13

### Install Dependencies

Clone the repository and run the installer — it will set up all required tools and libraries into a self-contained environment. To retain logs after installation, pass the `--keep-logs` flag.

```bash
git clone --recursive https://github.com/Mahfouz-z/hdlstuff.git
cd hdlstuff
python3 ubuntu-24.04-x86_64.py --keep-logs
```

Once installed, activate the environment before proceeding with any of the steps below:

```bash
source ~/.local/opt/hdlstuff/bin/activate-hdlstuff.sh
```

### Expected Dependencies

A successful installation provides the following tools and libraries.

#### Tools

| Tool | Version |
|------|---------|
| Python | 3.13 |
| GCC / G++ | System default on Ubuntu 24.04 (≥ 13.x) |
| CMake | 4.0.2 |
| Ninja | System default |
| GNU Make | System default |
| GNU Parallel | System default (`apt`) |
| GDB | System default |
| Verilator | 5.034 |
| sv2v | Installed from source via `install_sv2v` recipe in `hdlstuff` |
| SBT (Scala Build Tool) | Installed via Debian package (`hdlstuff` recipe) |
| GTKWave | System default (`apt`) |
| Mold (linker) | System default (`apt`) |
| ccache | System default (`apt`) |

#### Libraries

| Library | Version |
|---------|---------|
| Boost | 1.87.0 |
| fmtlib | 11.1.4 |
| SystemC (Accellera) | 3.0.1 |
| NumPy | Latest (`pip`) |
| Matplotlib | Latest (`pip`) |
| zlib | System default (`apt`: `zlib1g`, `zlib1g-dev`) |
| libfl | System default (`apt`: `libfl2`, `libfl-dev`) |
| libgoogle-perftools | System default (`apt`) |
| `hdlinfo`, `hdlscw`, `chext`, `sctlm`, `hdlstuff-hal` | Built from source (CMake Release / SBT `publishLocal`) via the `hdlstuff` repository |

---

## Running a Single Simulation

From the `architecture-generator` directory, generate a simulation for a given JSON task descriptor (e.g., `taskDescriptors/sweep1/sweep1_1.json`):

```bash
sbt "runMain HardCilk.MfpgaHardCilkEmitter taskDescriptors/sweep1/sweep1_1.json \
    -o output/ -r 32 -a -q paper_exp1"
```

| Flag | Description |
|------|-------------|
| `-o output/` | Output directory for the generated project |
| `-r 32` | Number of AXI ports used by the system |
| `-a` | Generate simulation software in addition to the hardware |
| `-q paper_exp1` | Name of the project set inside the JSON file |

Compile the generated simulation:

```bash
cd output/sweep1_1_hardcilk_output/software
mkdir build && cd build
cmake ..
make -j
```

Run the simulation:

```bash
./projects/paper_exp1/paper_exp1_systemc
```

You can pass parameters to control simulation behavior, for example:

```bash
./projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=16
```

> **`-Dexp1_delay`** controls the task size.

---

## Reproducing the Paper Results

### 1. Circuit Generation

Generate the benchmark circuits:

```bash
cd scripts
chmod +x generate_benchmarks.sh
./generate_benchmarks.sh
```

After this step, multiple projects will be generated under `architecture-generator/output/`.

### 2. Compile Simulations

```bash
cd scripts
chmod +x build_benchmarks.sh
./build_benchmarks.sh
```

After this step, each project under `output/` will contain a `software/build/` directory with the executable at `build/projects/<project_name>/<project_name>_systemc`.

### 3. Run Simulations

Run the three sweeps from the paper:

```bash
cd scripts
chmod +x run_sweep1.sh run_sweep2.sh run_sweep3.sh
./run_sweep1.sh
./run_sweep2.sh
./run_sweep3.sh
```

Results are written to `scripts/results/`, with a subdirectory per sweep. Each run produces a log file with efficiency reported at the end.

> **Note:** The run scripts use GNU `parallel` with **14 threads** by default. If jobs fail due to out-of-memory errors, reduce the thread count by editing the corresponding script. Our system has ~800 GB of RAM.

### 4. Generate Figures

To reproduce the simulation figure from the paper:

```bash
python3 one_figure.py <path_to_results_directory>
```

To reproduce the exact figure using our provided results:

```bash
python3 one_figure.py results-arxiv/
```

Our original results are included under `scripts/results-arxiv/`. This command generates `multi-fpga.pdf`, corresponding to Figure 12 in the paper.