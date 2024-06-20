#include <qsort.h>
#include <vector>
#include <algorithm>
#include <ctime>
#include <cstdlib>

std::vector<int> generate_unsorted_array(int n) {
    std::vector<int> array;
    // Generate numbers from 0 to n-1
    for (int i = 0; i < n; ++i) {
        array.push_back(i);
    }
    // Shuffle the array to make it unsorted
    std::srand(std::time(0)); // Seed the random number generator
    std::random_shuffle(array.begin(), array.end());
    return array;
}

qsortSpatialDescriptor create_qsort_task(hardCilkDriver *driver, int sortSize){
    // Create an array of unsorted integers of size sortSize
    std::vector<int> array = generate_unsorted_array(sortSize);

    // Allocate memory for the array on the FPGA
    uint64_t array_addr = driver->allocateMemFPGA(sortSize * sizeof(int));

    // Write the array to the FPGA memory
    driver->writeMem(array_addr, array.data(), sortSize * sizeof(int));

    // Allocate memory for the return address
    uint64_t return_addr = driver->allocateMemFPGA(sizeof(uint64_t));

    // Create a descriptor for the qsort task
    qsortSpatialDescriptor descriptor{
        .returnAddress = return_addr,
        .begin = array_addr,
        .end = array_addr + sortSize * sizeof(int),
        .padding = 0
    };  

    return descriptor;
}

bool check_sorted(int *array, int size){
    for (int i = 0; i < size - 1; ++i) {
        if (array[i] > array[i + 1]) {
            return false;
        }
    }
    return true;
}