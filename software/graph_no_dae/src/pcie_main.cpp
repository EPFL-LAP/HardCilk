#include <graphNoDaeDriver.h>
#include <memIO_pcie.h>
int main() {
    PCIeMemory memory;
    graphNoDaeDriver driver(&memory);
    driver.run_test_bench();
    return 0;
}