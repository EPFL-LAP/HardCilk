#include <graphDaeDriver.h>
#include <memIO_pcie.h>
int main() {
    PCIeMemory memory;
    graphDaeDriver driver(&memory);
    driver.run_test_bench();
    return 0;
}