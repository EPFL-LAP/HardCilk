#include <bfsDriver.h>
#include <memIO_pcie.h>
int main() {
    PCIeMemory memory;
    bfsDriver driver(&memory);
    driver.run_test_bench();
    return 0;
}