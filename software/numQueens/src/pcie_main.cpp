#include <numQueensDriver.h>
#include <memIO_pcie.h>

int main() {
    PCIeMemory memory;
    numQueensDriver driver(&memory);
    driver.run_test_bench();
    return 0;
}