# Before running, run the following command in the terminal
# . $HOME/.local/opt/hdlstuff/bin/activate-hdlstuff.sh

import os
import subprocess
from concurrent.futures import ThreadPoolExecutor

OUTPUT_DIR = "output/"
RESULTS_DIR = "results/sweep3"
HDLSTUFF_PREFIX = os.environ.get("HDLSTUFF_PREFIX", "")

max_parallel_threads = 14

# List of targets
compile_targets = [
    "sweep3_1",
    "sweep3_2_16",
    "sweep3_2_32",
    "sweep3_2_64",
    "sweep3_2_128",
    "sweep3_3_16",
    "sweep3_3_32",
    "sweep3_3_64",
    "sweep3_3_128",
    "sweep3_4_16",
    "sweep3_4_32",
    "sweep3_4_64",
    "sweep3_4_128",
]

light_mem_sweeps = [
    "sweep3_1",
    "sweep3_2_16",
    "sweep3_2_32",
    "sweep3_2_64",
    "sweep3_2_128"
]

medium_mem_sweeps = [
    "sweep3_3_16",
    "sweep3_3_32",
    "sweep3_3_64",
    "sweep3_3_128"
]

heavy_mem_sweeps = [
    "sweep3_4_16",
    "sweep3_4_32",
    "sweep3_4_64",
    "sweep3_4_128"
]



# List of delays
delays = [16, 32, 64, 128, 256, 512]

# Generate run commands from targets and delays
def generate_run_commands(targets, delays):
    commands = []
    for target in targets:
        exe_path = f"./{OUTPUT_DIR}{target}_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc"
        for delay in delays:
            result_path = f"{RESULTS_DIR}/{target}_exp3_delay{delay}"
            cmd = f"{exe_path} -Dexp3_delay={delay} > {result_path}"
            commands.append(cmd)
    return commands

run_commands_light = generate_run_commands(light_mem_sweeps, delays)
run_commands_medium = generate_run_commands(medium_mem_sweeps, delays)
run_commands_heavy = generate_run_commands(heavy_mem_sweeps, delays) + generate_run_commands(["sweep3_2_128", "sweep3_2_64"], [512])

def run_command(cmd):
    print(f"Running: {cmd}")
    subprocess.run(cmd, shell=True, check=True)

def build_target(target):
    print(f"Building {target}")
    sw_dir = os.path.join(OUTPUT_DIR, f"{target}_hardcilk_output/software")
    build_dir = os.path.join(sw_dir, "build")
    os.makedirs(build_dir, exist_ok=True)

    subprocess.run(["cmake", "..", f"-DCMAKE_PREFIX_PATH={HDLSTUFF_PREFIX}", "-G", "Ninja"], cwd=build_dir)
    subprocess.run(["ninja"], cwd=build_dir, check=True)

def main():
    for target in compile_targets:
        build_target(target)
    
    # with ThreadPoolExecutor(max_workers=4) as executor:
    #     executor.map(build_target, compile_targets)

    os.makedirs(RESULTS_DIR, exist_ok=True)

    # with ThreadPoolExecutor(max_workers=14) as executor:
    #     executor.map(run_command, run_commands_medium + run_commands_light)

    # with ThreadPoolExecutor(max_workers=10) as executor:
    #     executor.map(run_command, run_commands_medium)

    with ThreadPoolExecutor(max_workers=11) as executor:
        executor.map(run_command, run_commands_heavy)

    # with ThreadPoolExecutor(max_workers=max_parallel_threads) as executor:
    #     executor.map(run_command, run_commands_light)
        


if __name__ == "__main__":
    main()
