#include <fibonacciDriver.h>
#include <memIO_qdma.h>

#define RESET_ADDR 0x8000

int main() {
    QdmaMemory memory(getBoardHbmBaseAddress(), getBoardHbmSizeBytes(), getBoardRegBaseAddress());
    memory.writeReg32(RESET_ADDR, 0);
    sleep(1);
    memory.writeReg32(RESET_ADDR, 1);
    fibonacciDriver driver({&memory});
    driver.run_test_bench_mFpga();
    return 0;
}
