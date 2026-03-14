# FCCM'26 (NetCilk) — Simulation Artifact

## Platform Support

Ubuntu 24.04 (x86_64)

---

## Getting Started

### Prerequisites

- Python 3.13

### Install Dependencies

Clone the repository and run the installer — it will set up all required tools and libraries into a self-contained environment:

```bash
git clone --recursive https://github.com/Mahfouz-z/hdlstuff.git
cd hdlstuff
python3 ubuntu-24.04-x86_64.py
```

Once installed, activate the environment before proceeding with any of the steps below:

```bash
source ~/.local/opt/hdlstuff/bin/activate-hdlstuff.sh
```

---

## Reproducing the Paper Results

### 1. Circuit Generation

Generate the benchmark circuits:

```bash
cd scripts
./generate_benchmarks.sh
```

### 2. Compile Simulations

Compile simulations before running:
```bash
cd scripts
./build_benchmarks.sh
```

### 3. Run Simulations

Run the three sweeps from the paper:

```bash
cd scripts
./run_sweep1.sh
./run_sweep2.sh
./run_sweep3.sh
```

> **Note:** The run scripts use GNU `parallel` with **14 threads** by default. If some jobs die due to out-of-memory errors, reduce the thread count by editing the thread count in the corresponding script. Our system has ~800GB of RAM.

### 4. Collect Results

Simulation results (full run logs) are written to the `scripts/results/` directory, organized by sweep.

### 5. Generate Figures

To reproduce the simulation figure from the paper:

```bash
python3 one_figure.py <path_to_results_directory>
```

To reproduce the exact figure from the paper using our provided results:

```bash
python3 one_figure.py results-arxiv/
```

Our original results are included under `scripts/results-arxiv/`.