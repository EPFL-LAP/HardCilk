#include <quickSortDriver.h>
#include <pcieMemory.h>
int main() {
    PCIeMemory memory;
    quickSortDriver driver(&memory);
    driver.run_test_bench();
    return 0;
}