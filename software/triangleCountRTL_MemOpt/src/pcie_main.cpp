#include <triangleCountDriver.h>
#include <memIO_pcie.h>

#define RESET_ADDR 0x8000
int main() {
    PCIeMemory memory;
    memory.writeReg32(RESET_ADDR, 0);
    sleep(1);
    memory.writeReg32(RESET_ADDR, 1);
    triangleCountDriver driver(&memory);
    driver.run_test_bench();
    return 0;
}