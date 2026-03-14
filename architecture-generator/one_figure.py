import os
import numpy as np

# Create a plot for x-axis vs base series and the sweeps with a given index and same num of tasks served or stolen
import matplotlib.pyplot as plt
import numpy as np
import matplotlib.lines as mlines
import matplotlib.patches as mpatches
import matplotlib.cm as cm

import sys
import os

if len(sys.argv) < 2:
    print("Usage: python script.py <parent_directory>")
    sys.exit(1)

parent_dir = sys.argv[1]

# Expand ~ and environment variables just in case
parent_dir = os.path.expandvars(os.path.expanduser(parent_dir))

print(f"Parent directory of simulation results: {parent_dir}")



directory = parent_dir + "/sweep1/"

# Read the files in the directory
files = os.listdir(directory)

# sort files by the two numbers in the file name sweep2_<num1>_exp2_delay<num2>
# Wehere <num1> have precedence over <num2>

files = [f for f in files if "networkLatency" not in f]
print(files)

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
base_series_1 = [value for file, value in data_points[:6]]



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


directory = parent_dir + "/sweep2/"

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

def read_file2(file_path):
    with open(file_path, "r") as f:
        for line in f:
            if "T_n: " in line:
                # Extract the double value from the line
                value = line.split(": ")[1].strip()
                return float(value)
    return 0.0

# Create a list to store the data points
data_points = []

# Loop through the files and read the data
for file in files:
    file_path = os.path.join(directory, file)
    value = read_file2(file_path)
    data_points.append((file, value))

    

# print the data points with a new line each 6 elements
for i, (file, value) in enumerate(data_points):
    if i % 6 == 0 and i != 0:
        print()
    print(f"{file}   {value}")
    
print()

# base series are the first 5 data points of the files without the file name

single_pe = [value for file, value in data_points[:6]]

# remove this for correct results
#single_pe[-1] = single_pe[4]*1.992

base_series_2 = [value for file, value in data_points[6:12]]



base_series_2 = (np.array(single_pe) / 128 / np.array(base_series_2)) * 100




# create a map from a pair of (x,y) to an array of values
sweep_map = {}
for i, (file, value) in enumerate(data_points):
    if i < 12:
        continue
    # get the x and y values from the file name
    x = int(file.split("_")[1]) # sweep index
    y = int(file.split("_")[2]) # num of tasks served or stolen
    # add the value to the map
    if (x,y) not in sweep_map:
        sweep_map[(x,y)] = []
    sweep_map[(x,y)].append(value)



sweep_2_2_16 = np.array(single_pe) / 128 / np.array(sweep_map[(2,16)]) * 100
sweep_2_2_32 = np.array(single_pe) / 128 / np.array(sweep_map[(2,32)]) * 100
sweep_2_2_64 = np.array(single_pe) / 128 / np.array(sweep_map[(2,64)]) * 100
sweep_2_2_128 = np.array(single_pe) / 128 / np.array(sweep_map[(2,128)]) * 100
sweep_2_3_16 = np.array(single_pe) / 128 / np.array(sweep_map[(3,16)]) * 100
sweep_2_3_32 = np.array(single_pe) / 128 / np.array(sweep_map[(3,32)]) * 100
sweep_2_3_64 = np.array(single_pe) / 128 / np.array(sweep_map[(3,64)]) * 100
sweep_2_3_128 = np.array(single_pe) / 128 / np.array(sweep_map[(3,128)]) * 100 
sweep_2_4_16 = np.array(single_pe) / 128 / np.array(sweep_map[(4,16)]) * 100 
sweep_2_4_32 = np.array(single_pe) / 128 / np.array(sweep_map[(4,32)]) * 100
sweep_2_4_64 = np.array(single_pe) / 128 / np.array(sweep_map[(4,64)]) * 100
sweep_2_4_128 = np.array(single_pe) / 128 / np.array(sweep_map[(4,128)]) * 100


directory = parent_dir + "/sweep3/"

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
base_series_3 = [value for file, value in data_points[:6]]

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
    

sweep_3_2_16 = sweep_map[(2,16)]
sweep_3_2_32 = sweep_map[(2,32)]
sweep_3_2_64 = sweep_map[(2,64)]
sweep_3_2_128 = sweep_map[(2,128)]
sweep_3_3_16 = sweep_map[(3,16)]
sweep_3_3_32 = sweep_map[(3,32)]
sweep_3_3_64 = sweep_map[(3,64)]
sweep_3_3_128 = sweep_map[(3,128)]
sweep_3_4_16 = sweep_map[(4,16)]
sweep_3_4_32 = sweep_map[(4,32)]
sweep_3_4_64 = sweep_map[(4,64)]
sweep_3_4_128 = sweep_map[(4,128)]


x_axis = [16,32,64,128,256, 512]

fig, axs = plt.subplots(3, 4, figsize=(15, 8), sharex=True)

axs[0, 0].set_title("(a) Benchmark 1, 16 task per request")
axs[0, 0].set_ylabel("Efficiency (%)")
axs[0, 0].set_xlabel("Task Delay (Cycles)")
axs[0, 0].plot(x_axis, base_series_1, marker='o', label='1 FPGA')
axs[0, 0].plot(x_axis, sweep_1_2_16, marker='s', label='2 FPGA')
axs[0, 0].plot(x_axis, sweep_1_3_16, marker='^', label='4 FPGA')
axs[0, 0].plot(x_axis, sweep_1_4_16, marker='x', label='8 FPGA')
axs[0, 0].set_ylim(20, 101)
axs[0, 0].set_xlim(0, 512)
axs[0, 0].legend(loc='lower right')
axs[0, 0].grid(True, which='both', linestyle='--', linewidth=1, alpha=0.7, color='gray')  

axs[0, 1].set_title("(b) Benchmark 1, 32 task per request")
axs[0, 1].set_ylabel("Efficiency (%)")
axs[0, 1].set_xlabel("Task Delay (Cycles)")
axs[0, 1].plot(x_axis, base_series_1, marker='o', label='1 FPGA')
axs[0, 1].plot(x_axis, sweep_1_2_32, marker='s', label='2 FPGA')
axs[0, 1].plot(x_axis, sweep_1_3_32, marker='^', label='4 FPGA')
axs[0, 1].plot(x_axis, sweep_1_4_32, marker='x', label='8 FPGA')
axs[0, 1].set_ylim(20, 101)
axs[0, 1].set_xlim(0, 512)
axs[0, 1].legend(loc='lower right')
axs[0, 1].grid(True, which='both', linestyle='--', linewidth=1, alpha=0.7, color='gray')  

axs[0, 2].set_title("(c) Benchmark 1, 64 task per request")
axs[0, 2].set_ylabel("Efficiency (%)")
axs[0, 2].set_xlabel("Task Delay (Cycles)")
axs[0, 2].plot(x_axis, base_series_1, marker='o', label='1 FPGA')
axs[0, 2].plot(x_axis, sweep_1_2_64, marker='s', label='2 FPGA')
axs[0, 2].plot(x_axis, sweep_1_3_64, marker='^', label='4 FPGA')
axs[0, 2].plot(x_axis, sweep_1_4_64, marker='x', label='8 FPGA')
axs[0, 2].set_ylim(20, 101)
axs[0, 2].set_xlim(0, 512)
axs[0, 2].legend(loc='lower right')
axs[0, 2].grid(True, which='both', linestyle='--', linewidth=1, alpha=0.7, color='gray')  

axs[0, 3].set_title("(d) Benchmark 1, 128 task per request")
axs[0, 3].set_ylabel("Efficiency (%)")
axs[0, 3].set_xlabel("Task Delay (Cycles)")
axs[0, 3].plot(x_axis, base_series_1, marker='o', label='1 FPGA')
axs[0, 3].plot(x_axis, sweep_1_2_128, marker='s', label='2 FPGA')
axs[0, 3].plot(x_axis, sweep_1_3_128, marker='^', label='4 FPGA')
axs[0, 3].plot(x_axis, sweep_1_4_128, marker='x', label='8 FPGA')
axs[0, 3].set_ylim(20, 101)
axs[0, 3].set_xlim(0, 512)
axs[0, 3].legend(loc='lower right')
axs[0, 3].grid(True, which='both', linestyle='--', linewidth=1, alpha=0.7, color='gray')  


axs[1, 0].set_title("(e) Benchmark 2, 16 task per request")
axs[1, 0].set_ylabel("Efficiency (%)")
axs[1, 0].set_xlabel("Task Delay (Cycles)")
axs[1, 0].plot(x_axis, base_series_2, marker='o', label='1 FPGA')
axs[1, 0].plot(x_axis, sweep_2_2_16, marker='s', label='2 FPGA')
axs[1, 0].plot(x_axis, sweep_2_3_16, marker='^', label='4 FPGA')
axs[1, 0].plot(x_axis, sweep_2_4_16, marker='x', label='8 FPGA')
axs[1, 0].set_ylim(20, 101)
axs[1, 0].set_xlim(0, 512)
axs[1, 0].legend(loc='lower right')
axs[1, 0].grid(True, which='both', linestyle='--', linewidth=1, alpha=0.7, color='gray')  

axs[1, 1].set_title("(f) Benchmark 2, 32 task per request")
axs[1, 1].set_ylabel("Efficiency (%)")
axs[1, 1].set_xlabel("Task Delay (Cycles)")
axs[1, 1].plot(x_axis, base_series_2, marker='o', label='1 FPGA')
axs[1, 1].plot(x_axis, sweep_2_2_32, marker='s', label='2 FPGA')
axs[1, 1].plot(x_axis, sweep_2_3_32, marker='^', label='4 FPGA')
axs[1, 1].plot(x_axis, sweep_2_4_32, marker='x', label='8 FPGA')
axs[1, 1].set_ylim(20, 101)
axs[1, 1].set_xlim(0, 512)
axs[1, 1].legend(loc='lower right')
axs[1, 1].grid(True, which='both', linestyle='--', linewidth=1, alpha=0.7, color='gray')  

axs[1, 2].set_title("(g) Benchmark 2, 64 task per request")
axs[1, 2].set_ylabel("Efficiency (%)")
axs[1, 2].set_xlabel("Task Delay (Cycles)")
axs[1, 2].plot(x_axis, base_series_2, marker='o', label='1 FPGA')
axs[1, 2].plot(x_axis, sweep_2_2_64, marker='s', label='2 FPGA')
axs[1, 2].plot(x_axis, sweep_2_3_64, marker='^', label='4 FPGA')
axs[1, 2].plot(x_axis, sweep_2_4_64, marker='x', label='8 FPGA')
axs[1, 2].set_ylim(20, 101)
axs[1, 2].set_xlim(0, 512)
axs[1, 2].legend(loc='lower right')
axs[1, 2].grid(True, which='both', linestyle='--', linewidth=1, alpha=0.7, color='gray')  

axs[1, 3].set_title("(h) Benchmark 2, 128 task per request")
axs[1, 3].set_ylabel("Efficiency (%)")
axs[1, 3].set_xlabel("Task Delay (Cycles)")
axs[1, 3].plot(x_axis, base_series_2, marker='o', label='1 FPGA')
axs[1, 3].plot(x_axis, sweep_2_2_128, marker='s', label='2 FPGA')
axs[1, 3].plot(x_axis, sweep_2_3_128, marker='^', label='4 FPGA')
axs[1, 3].plot(x_axis, sweep_2_4_128, marker='x', label='8 FPGA')
axs[1, 3].set_ylim(20, 101)
axs[1, 3].set_xlim(0, 512)
axs[1, 3].legend(loc='lower right')
axs[1, 3].grid(True, which='both', linestyle='--', linewidth=1, alpha=0.7, color='gray')  

axs[2, 0].set_title("(i) Benchmark 3, 16 task per request")
axs[2, 0].set_ylabel("Efficiency (%)")
axs[2, 0].set_xlabel("Task Delay (Cycles)")
axs[2, 0].plot(x_axis, base_series_3, marker='o', label='1 FPGA')
axs[2, 0].plot(x_axis, sweep_3_2_16, marker='s', label='2 FPGA')
axs[2, 0].plot(x_axis, sweep_3_3_16, marker='^', label='4 FPGA')
axs[2, 0].plot(x_axis, sweep_3_4_16, marker='x', label='8 FPGA')
axs[2, 0].set_ylim(15, 101)
axs[2, 0].set_xlim(0, 512)
axs[2, 0].legend(loc='lower right')
axs[2, 0].grid(True, which='both', linestyle='--', linewidth=1, alpha=0.7, color='gray')  

axs[2, 1].set_title("(i) Benchmark 3, 32 task per request")
axs[2, 1].set_ylabel("Efficiency (%)")
axs[2, 1].set_xlabel("Task Delay (Cycles)")
axs[2, 1].plot(x_axis, base_series_3, marker='o', label='1 FPGA')
axs[2, 1].plot(x_axis, sweep_3_2_32, marker='s', label='2 FPGA')
axs[2, 1].plot(x_axis, sweep_3_3_32, marker='^', label='4 FPGA')
axs[2, 1].plot(x_axis, sweep_3_4_32, marker='x', label='8 FPGA')
axs[2, 1].set_ylim(15, 101)
axs[2, 1].set_xlim(0, 512)
axs[2, 1].legend(loc='lower right')
axs[2, 1].grid(True, which='both', linestyle='--', linewidth=1, alpha=0.7, color='gray')  

axs[2, 2].set_title("(j) Benchmark 3, 64 task per request")
axs[2, 2].set_ylabel("Efficiency (%)")
axs[2, 2].set_xlabel("Task Delay (Cycles)")
axs[2, 2].plot(x_axis, base_series_3, marker='o', label='1 FPGA')
axs[2, 2].plot(x_axis, sweep_3_2_64, marker='s', label='2 FPGA')
axs[2, 2].plot(x_axis, sweep_3_3_64, marker='^', label='4 FPGA')
axs[2, 2].plot(x_axis, sweep_3_4_64, marker='x', label='8 FPGA')
axs[2, 2].set_ylim(15, 101)
axs[2, 2].set_xlim(0, 512)
axs[2, 2].legend(loc='lower right')
axs[2, 2].grid(True, which='both', linestyle='--', linewidth=1, alpha=0.7, color='gray')  

axs[2, 3].set_title("(k) Benchmark 3, 128 task per request")
axs[2, 3].set_ylabel("Efficiency (%)")
axs[2, 3].set_xlabel("Task Delay (Cycles)")
axs[2, 3].plot(x_axis, base_series_3, marker='o', label='1 FPGA')
axs[2, 3].plot(x_axis, sweep_3_2_128, marker='s', label='2 FPGA')
axs[2, 3].plot(x_axis, sweep_3_3_128, marker='^', label='4 FPGA')
axs[2, 3].plot(x_axis, sweep_3_4_128, marker='x', label='8 FPGA')
axs[2, 3].set_ylim(15, 101)
axs[2, 3].set_xlim(0, 512)
axs[2, 3].legend(loc='lower right')
axs[2, 3].grid(True, which='both', linestyle='--', linewidth=1, alpha=0.7, color='gray')  


# Tight layout for clean spacing
plt.tight_layout()
plt.savefig('multi-fpga.pdf', dpi=300)