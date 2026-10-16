#include <triangleCountDriver.h>
#include <memIO_pcie.h>
#include <cassert>
#include <string>

// Segment pinned by the generated block design:
//   set_property offset 0x0008000 [get_bd_addr_segs {xdma_0/M_AXI_LITE/SEG_axi_gpio_0_Reg}]
// Writing 0 then 1 pulses the AXI GPIO that gates proc_sys_reset_1.
#define RESET_ADDR 0x8000

int main(int argc, char* argv[]) {
    assert(argc == 2);

    std::string graph_file = argv[1];

    PCIeMemory memory;

    memory.writeReg32(RESET_ADDR, 0);
    sleep(1);
    memory.writeReg32(RESET_ADDR, 1);

    triangleCountDriver driver(&memory, graph_file);
    driver.run_test_bench();

    return 0;
}
