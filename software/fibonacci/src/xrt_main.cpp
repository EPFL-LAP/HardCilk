#include <fibonacciDriver.h>
#include <memIO_xrt.h>
#include <experimental/xrt_xclbin.h>
#include <experimental/xrt_ip.h>
#include <string>

// main parses args
int main(int argc, char* argv[])
{

    // The first argument is the pass to the xclbin
    std::string xclbin_path = argv[1];

    assert(argc == 2);


    // load the xclbin to the fpga
    auto device = xrt::device(0);
    auto uuid = device.load_xclbin(xclbin_path);
    auto fibonacci_0 = xrt::ip(device, uuid, "fibonacci:{fibonacci_0}");
    auto fibonacci_1 = xrt::ip(device, uuid, "fibonacci:{fibonacci_1}");

    std::vector<Memory *> memories_;

    auto memory_0 = XRTMemory(device, fibonacci_0);
    auto memory_1 = XRTMemory(device, fibonacci_1);

    memories_.push_back(&memory_0);
    memories_.push_back(&memory_1);
    

    fibonacciDriver driver(memories_);
    driver.run_test_bench_mFpga();
    return 0;
}