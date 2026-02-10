#include <pageRankDriver.h>
#include <memIO_xrt.h>
#include <experimental/xrt_xclbin.h>
#include <experimental/xrt_ip.h>
#include <string>

#include <netLayer.hpp>


static AlveoLink::common::FPGA fpga_card; // single FPGA
static AlveoLink::network_roce_v2::NetLayer<2> l_netLayer; // network layer for 2 ports


// main parses args
int main(int argc, char* argv[])
{

     assert(argc == 4);
     
    // The first argument is the pass to the xclbin
    std::string xclbin_path = argv[1];

   

    std::string graph_file = argv[2];

    int enable_alveo_link = atoi(argv[3]);

    fpga_card.setId(0);
    fpga_card.load_xclbin(xclbin_path);



    // load the xclbin to the fpga
    auto device = fpga_card.getDevice();
    auto uuid = fpga_card.getUUID();

    auto pr0_name = "pageRank:{pageRank_0}";
    auto pr1_name = "pageRank:{pageRank_1}";

    if(enable_alveo_link){
        l_netLayer.init(&fpga_card);
        for (auto j = 0u; j < 2u; ++j) {
            l_netLayer.setIPSubnet(j, 0x0000a8c0);      // setting the IP subnet for HiveNet
            l_netLayer.setMACSubnet(j, 0x347844332211); // setting the MAC subnet for HiveNet
            l_netLayer.setID(j, j);                 // set HiveNet ID for each port (1 and 2)
            l_netLayer.setVlanPFC(j, false);            // VLAN off by default
        }
        //// // Enable RS-FEC and flow control per port
        for (auto j = 0u; j < 2u; ++j) {
            std::cout << "INFO: turn on RS_FEC and configuring flow control for port " << j << std::endl;
            l_netLayer.turnOn_RS_FEC(j, true);
            l_netLayer.turnOn_flow_control(j, true);
        }
        // // Wait for both links to come up
        unsigned int linksUpCount = 0;
        while (linksUpCount < 1) {
            std::cout << "INFO: Waiting for links up (" << linksUpCount << " of 1)\n";
            if (l_netLayer.linksUp()) {
                linksUpCount +=1;
            } else {
                sleep(1);
            }
        }
        pr0_name = "pageRank_0:{pageRank_0}";
        pr1_name = "pageRank_1:{pageRank_1}";
    }



    auto pageRank_0 = xrt::ip(device, uuid, pr0_name);
    auto pageRank_1 = xrt::ip(device, uuid, pr1_name);


    std::vector<Memory *> memories_;

    auto memory_0 = XRTMemory(device, pageRank_0);
    auto memory_1 = XRTMemory(device, pageRank_1);

    memories_.push_back(&memory_0);
    memories_.push_back(&memory_1);
    

    pageRankDriver driver(memories_, graph_file);
    driver.run_test_bench_mFpga();
    return 0;
}