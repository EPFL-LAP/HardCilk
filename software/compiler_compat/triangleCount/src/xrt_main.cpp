#include <triangleCountDriver.h>
#include <memIO_xrt.h>
#include <experimental/xrt_xclbin.h>
#include <experimental/xrt_ip.h>
#include <iostream>
#include <string>
#include <xrt/xrt_device.h>

static void printUsage(const char *program)
{
    std::cerr << "Usage: " << program << " <xclbin> <graph_file>\n";
}

// main parses args
int main(int argc, char* argv[])
{
    if (argc == 2 && (std::string(argv[1]) == "--help" || std::string(argv[1]) == "-h")) {
        printUsage(argv[0]);
        return 0;
    }
    if (argc != 3) {
        printUsage(argv[0]);
        return 1;
    }

    std::string xclbin_path = argv[1];
    std::string graph_file = argv[2];

    auto pr0_name = "triangleCount_0:{triangleCount_0}";

    xrt::device fpga_card(0);
    auto uuid = fpga_card.load_xclbin(xclbin_path);

    auto triangleCount_0 = xrt::ip(fpga_card, uuid, pr0_name);

    auto memory_0 = XRTMemory(fpga_card, triangleCount_0);

    triangleCountDriver driver(&memory_0, graph_file);
    driver.run_test_bench();


    return 0;
}
