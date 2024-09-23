#include <fibonacciDriver.h>
#include <pcieMemory.h>
int main() {
    PCIeMemory memory;
    fibonacciDriver driver(&memory);
    driver.run_test_bench();
    return 0;
}