import os

directory = "results/sweep3/"

# Read the files in the directory
files = os.listdir(directory)

# sort files by the two numbers in the file name sweep2_<num1>_exp2_delay<num2>
# Wehere <num1> have precedence over <num2>

# find files with 3 underscores and sort them by the two numbers
files1_1 = [f for f in files if f.count("_") == 3]
# sort files by the two numbers in the file name sweep1_<num1>_exp2_delay<num2>
# Wehere <num1> have precedence over <num2>
files1_1 = sorted(files1_1, key=lambda x: (int(x.split("_")[1]), int(x.split("_")[3].split("y")[1])))

other_files = [f for f in files if f.count("_") != 3]
# sort the other files by the three numbers in the file name sweep1_<num1>_<num2>_exp2_delay<num3>
# Wehere <num1> have precedence over <num2> and <num3>
other_files = sorted(other_files, key=lambda x: (int(x.split("_")[1]), int(x.split("_")[2]), int(x.split("_")[4].split("y")[1])))
# combine the two lists
files = files1_1 + other_files

# read the files and check if they have the line "T_n: <double>"
# if true read the double, if not set to zero

def read_file(file_path):
    with open(file_path, "r") as f:
        for line in f:
            if "Efficiency: " in line:
                # Extract the double value from the line
                value = line.split(": ")[1].strip().split("%")[0]
                return float(value)
    return 0.0

# Create a list to store the data points
data_points = []

# Loop through the files and read the data
for file in files:
    file_path = os.path.join(directory, file)
    value = read_file(file_path)
    data_points.append((file, value))

    

# print the data points with a new line each 5 elements
for i, (file, value) in enumerate(data_points):
    if i % 6 == 0 and i != 0:
        print()
    print(f"{file}   {value}")
    
print()

# base series are the first 5 data points of the files without the file name
base_series = [value for file, value in data_points[:6]]

x_axis = [16,32,64,128,256, 512]


# create a map from a pair of (x,y) to an array of values
sweep_map = {}
for i, (file, value) in enumerate(data_points):
    if i < 6:
        continue
    # get the x and y values from the file name
    x = int(file.split("_")[1]) # sweep index
    y = int(file.split("_")[2]) # num of tasks served or stolen
    # add the value to the map
    if (x,y) not in sweep_map:
        sweep_map[(x,y)] = []
    sweep_map[(x,y)].append(value)


# Create a plot for x-axis vs base series and the sweeps with a given index and same num of tasks served or stolen
import matplotlib.pyplot as plt
import numpy as np
import matplotlib.lines as mlines
import matplotlib.patches as mpatches
import matplotlib.cm as cm



sweep_1_2_16 = sweep_map[(2,16)]
sweep_1_2_32 = sweep_map[(2,32)]
sweep_1_2_64 = sweep_map[(2,64)]
sweep_1_2_128 = sweep_map[(2,128)]
sweep_1_3_16 = sweep_map[(3,16)]
sweep_1_3_32 = sweep_map[(3,32)]
sweep_1_3_64 = sweep_map[(3,64)]
sweep_1_3_128 = sweep_map[(3,128)]
sweep_1_4_16 = sweep_map[(4,16)]
sweep_1_4_32 = sweep_map[(4,32)]
sweep_1_4_64 = sweep_map[(4,64)]
sweep_1_4_128 = sweep_map[(4,128)]

fig, axs = plt.subplots(1, 4, figsize=(16, 4), sharex=True)

axs[0].set_title("(a) Benchmark 3, 16 task per request")
axs[0].set_ylabel("Efficiency (%)")
axs[0].set_xlabel("Task Delay (Cycles)")
axs[0].plot(x_axis, base_series, marker='o', label='1 FPGA')
axs[0].plot(x_axis, sweep_1_2_16, marker='s', label='2 FPGA')
axs[0].plot(x_axis, sweep_1_3_16, marker='^', label='4 FPGA')
axs[0].plot(x_axis, sweep_1_4_16, marker='x', label='8 FPGA')
axs[0].set_ylim(15, 101)
axs[0].set_xlim(0, 512)
axs[0].legend(loc='lower right')
axs[0].grid(True, which='both', linestyle='--', linewidth=1, alpha=0.7, color='gray')  

axs[1].set_title("(b) Benchmark 3, 32 task per request")
axs[1].set_ylabel("Efficiency (%)")
axs[1].set_xlabel("Task Delay (Cycles)")
axs[1].plot(x_axis, base_series, marker='o', label='1 FPGA')
axs[1].plot(x_axis, sweep_1_2_32, marker='s', label='2 FPGA')
axs[1].plot(x_axis, sweep_1_3_32, marker='^', label='4 FPGA')
axs[1].plot(x_axis, sweep_1_4_32, marker='x', label='8 FPGA')
axs[1].set_ylim(15, 101)
axs[1].set_xlim(0, 512)
axs[1].legend(loc='lower right')
axs[1].grid(True, which='both', linestyle='--', linewidth=1, alpha=0.7, color='gray')  

axs[2].set_title("(c) Benchmark 3, 64 task per request")
axs[2].set_ylabel("Efficiency (%)")
axs[2].set_xlabel("Task Delay (Cycles)")
axs[2].plot(x_axis, base_series, marker='o', label='1 FPGA')
axs[2].plot(x_axis, sweep_1_2_64, marker='s', label='2 FPGA')
axs[2].plot(x_axis, sweep_1_3_64, marker='^', label='4 FPGA')
axs[2].plot(x_axis, sweep_1_4_64, marker='x', label='8 FPGA')
axs[2].set_ylim(15, 101)
axs[2].set_xlim(0, 512)
axs[2].legend(loc='lower right')
axs[2].grid(True, which='both', linestyle='--', linewidth=1, alpha=0.7, color='gray')  

axs[3].set_title("(d) Benchmark 3, 128 task per request")
axs[3].set_ylabel("Efficiency (%)")
axs[3].set_xlabel("Task Delay (Cycles)")
axs[3].plot(x_axis, base_series, marker='o', label='1 FPGA')
axs[3].plot(x_axis, sweep_1_2_128, marker='s', label='2 FPGA')
axs[3].plot(x_axis, sweep_1_3_128, marker='^', label='4 FPGA')
axs[3].plot(x_axis, sweep_1_4_128, marker='x', label='8 FPGA')
axs[3].set_ylim(15, 101)
axs[3].set_xlim(0, 512)
axs[3].legend(loc='lower right')
axs[3].grid(True, which='both', linestyle='--', linewidth=1, alpha=0.7, color='gray')  


# Tight layout for clean spacing
plt.tight_layout()
plt.savefig('Benchmark3.pdf', dpi=300)