#include <quickSortDriver.h>
#include <memIO_pcie.h>
int main() {
    PCIeMemory memory;
    quickSortDriver driver(&memory);
    driver.run_test_bench();
    return 0;
}