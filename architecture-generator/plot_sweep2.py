import os

directory = "results/sweep2/"

# Read the files in the directory
files = os.listdir(directory)

# sort files by the two numbers in the file name sweep2_<num1>_exp2_delay<num2>
# Wehere <num1> have precedence over <num2>
files = sorted(files, key=lambda x: (int(x.split("_")[1]), int(x.split("_")[3].split("y")[1])))



# read the files and check if they have the line "T_n: <double>"
# if true read the double, if not set to zero

def read_file(file_path):
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
    value = read_file(file_path)
    data_points.append((file, value))
        
# print the data points with a new line each 5 elements
for i, (file, value) in enumerate(data_points):
    if i % 5 == 0 and i != 0:
        print()
    print(f"{file}   {value}")
    
