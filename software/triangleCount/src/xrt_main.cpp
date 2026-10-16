#include <triangleCountDriver.h>
#include <memIO_xrt.h>
#include <experimental/xrt_xclbin.h>
#include <experimental/xrt_ip.h>
#include <cassert>
#include <string>
#include <xrt/xrt_device.h>

// main parses args
int main(int argc, char* argv[])
{
    // The graph path is fixed inside triangleCountDriver::run_test_bench.
    assert(argc == 2);

    std::string xclbin_path = argv[1];

    auto pr0_name = "triangleCount_0:{triangleCount_0}";

    xrt::device fpga_card(0);
    auto uuid = fpga_card.load_xclbin(xclbin_path);

    auto triangleCount_0 = xrt::ip(fpga_card, uuid, pr0_name);

    auto memory_0 = XRTMemory(fpga_card, triangleCount_0);

    triangleCountDriver driver(&memory_0);
    driver.run_test_bench();


    return 0;
}