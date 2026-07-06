#include <randomWalkDriver.h>
#include <memIO_xrt.h>
#include <experimental/xrt_xclbin.h>
#include <experimental/xrt_ip.h>
#include <string>
#include <xrt/xrt_device.h>

// main parses args
int main(int argc, char* argv[])
{
    assert(argc == 3);

    std::string xclbin_path = argv[1];
    std::string graph_file = argv[2];

    auto pr0_name = "randomWalk_0:{randomWalk_0}";

    xrt::device fpga_card(0);
    auto uuid = fpga_card.load_xclbin(xclbin_path);

    auto randomWalk_0 = xrt::ip(fpga_card, uuid, pr0_name);

    auto memory_0 = XRTMemory(fpga_card, randomWalk_0);

    randomWalkDriver driver(&memory_0, graph_file);
    driver.run_test_bench();


    return 0;
}