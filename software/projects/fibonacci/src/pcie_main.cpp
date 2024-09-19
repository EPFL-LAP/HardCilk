#include <fibonacciDriver.h>
#include <memIO_pcie.h>
int main() {
    PCIeMemory memory;
    fibonacciDriver driver(&memory);
    driver.run_test_bench();
    return 0;
}