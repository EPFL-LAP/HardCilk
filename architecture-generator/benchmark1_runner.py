# Before running, run the following command in the terminal
# . $HOME/.local/opt/hdlstuff/bin/activate-hdlstuff.sh

import os
import subprocess
from concurrent.futures import ThreadPoolExecutor

OUTPUT_DIR = "output/"
RESULTS_DIR = "results/sweep1"
HDLSTUFF_PREFIX = os.environ.get("HDLSTUFF_PREFIX", "")

max_parallel_threads = 14

# List of targets
compile_targets = [
#    "sweep1_1",
    "sweep1_2_16",
    "sweep1_2_32",
    "sweep1_2_64",
    "sweep1_2_128",
    "sweep1_3_16",
    "sweep1_3_32",
    "sweep1_3_64",
    "sweep1_3_128",
    "sweep1_4_16",
    "sweep1_4_32",
    "sweep1_4_64",
    "sweep1_4_128",
]

light_mem_sweeps = [
    "sweep1_1",
    "sweep1_2_16",
    "sweep1_2_32",
    "sweep1_2_64",
    "sweep1_2_128"
]

medium_mem_sweeps = [
    "sweep1_3_16",
    "sweep1_3_32",
    "sweep1_3_64",
    "sweep1_3_128"
]

heavy_mem_sweeps = [
    "sweep1_4_16",
    "sweep1_4_32",
    "sweep1_4_64",
    "sweep1_4_128"
]



# List of delays
delays = [16, 32, 64, 128, 256, 512]

# Generate run commands from targets and delays
def generate_run_commands(targets, delays, network_latency = 0):
    commands = []
    for target in targets:
        exe_path = f"./{OUTPUT_DIR}{target}_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc"
        for delay in delays:
            result_path = ""
            cmd = ""
            if(network_latency == 0):
                result_path = f"{RESULTS_DIR}/{target}_exp1_delay{delay}"
            else:
                result_path = f"{RESULTS_DIR}/{target}_exp1_delay{delay}_networkLatency{network_latency}"
                
            if(network_latency == 0):
                cmd = f"{exe_path} -Dexp1_delay={delay} > {result_path}"
            else:
                cmd = f"{exe_path} -Dexp1_delay={delay} -DmFpga_linkToSwitchDelay='{network_latency}_ns' -DmFpga_linkToFpgaDelay='{network_latency}_ns'  > {result_path}"
                
            commands.append(cmd)
    return commands

#run_commands_network_exp = generate_run_commands(compile_targets, delays, 2048)

# run_commands_missing =  generate_run_commands(["sweep1_4_16"], [64,128,256,512], 2048)
# run_commands_missing += generate_run_commands(["sweep1_4_32"], [32,64,128,256], 2048)

run_commands_missing  = generate_run_commands(["sweep1_4_64"], [16,32,64], 2048)

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

    with ThreadPoolExecutor(max_workers=14) as executor:
        executor.map(run_command, run_commands_missing)

    # with ThreadPoolExecutor(max_workers=14) as executor:
    #     executor.map(run_command, run_commands)

    # with ThreadPoolExecutor(max_workers=max_parallel_threads) as executor:
    #     executor.map(run_command, run_commands_light)
        


if __name__ == "__main__":
    main()
