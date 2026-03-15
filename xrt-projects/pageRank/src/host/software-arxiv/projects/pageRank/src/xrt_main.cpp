#include <pageRankDriver.h>
#include <memIO_xrt.h>
#include <experimental/xrt_xclbin.h>
#include <experimental/xrt_ip.h>
#include <string>


#include <vnx/cmac.hpp>
#include <vnx/networklayer.hpp>
#include <xrt/xrt_device.h>
#include <json/json.h>


#define FPGA_COUNT 2


 
// main parses args
int main(int argc, char* argv[])
{

    xrt::device fpga_card[FPGA_COUNT]; 
    xrt::uuid  uuids[FPGA_COUNT];
    //vnx::Networklayer networkLayers[FPGA_COUNT];
    //std::vector<vnx::Networklayer> networkLayers;
    std::vector<vnx::CMAC> cmacs;
    // vnx::CMAC cmacs[FPGA_COUNT];

    assert(argc == 4);
     
    // The first argument is the pass to the xclbin
    std::string xclbin_path = argv[1];

   

    std::string graph_file = argv[2];

    int enable_VNx = atoi(argv[3]);

    for (int i = 0; i < FPGA_COUNT; i++) {
        fpga_card[i] = xrt::device(i); 
        uuids[i] = fpga_card[i].load_xclbin(xclbin_path);
        //networkLayers.push_back(vnx::Networklayer(xrt::ip(fpga_card[i], uuids[i], "networklayer:{networklayer_0}")));
        cmacs.push_back(vnx::CMAC(xrt::ip(fpga_card[i], uuids[i], "cmac_0")));
    }

	sleep(1);

    auto pr0_name = "pageRank_0:{pageRank_0}";



    if(enable_VNx){

        for (auto i=0; i<FPGA_COUNT; ++i) {
            // Enable rsfec if necessary
            cmacs[i].set_rs_fec(true);
            cmacs[i].send_init_packets();

        }

        for (auto i=0; i<FPGA_COUNT; ++i) {
            bool link_status;
            // Can take a few tries before link is ready.
            for (std::size_t j = 0; j < 5; ++j) {
                auto status = cmacs[i].link_status();
                link_status = status["rx_status"];
                if (link_status) {
                    break;
                }
                std::this_thread::sleep_for(std::chrono::seconds(1));
            }
            std::cout << "Link interface " << i << ": "
                    << (link_status ? "true" : "false") << std::endl;
            std::cout << "RS-FEC enabled: " << (cmacs[i].get_rs_fec() ? "true" : "false")
                    << std::endl;
        }

        sleep(10);
        for (auto i=0; i<FPGA_COUNT; ++i) {
            cmacs[i].stop_init_packets();
            cmacs[i].set_flow_control();
        }
        sleep(10);
        
     

        for (auto i=0; i<FPGA_COUNT; ++i) {
            std::map<std::string, bool> status = cmacs[i].link_status();
            for (auto entry : status) {
                std::cout << "Entry: " << entry.first << " Value: " << entry.second << std::endl;
            }
        }

        // std::string ip[FPGA_COUNT];
        // ip[0] = "10.1.212.165";
        // ip[1] = "10.1.212.167";


        // for (auto i=0; i<FPGA_COUNT; ++i) {
  
        //     std::string my_ip = ip[i];
        //     networkLayers[i].update_ip_address(my_ip);


        //     uint16_t my_port = i;
        //     uint16_t their_port = 1-i;
        //     std::string their_ip = ip[1-i];
        


        //     // networkLayers[i].configure_socket(0, their_ip, their_port, my_port, true);
        //     // networkLayers[i].populate_socket_table();
        //     // networkLayers[i].print_socket_table(1);
        //     networkLayers[i].do_custom();

        // }

        // for (auto i=0; i<FPGA_COUNT; ++i) {
        //     std::string my_ip = ip[i];
        //     uint16_t my_port = i;
        //     uint16_t their_port = 1-i;
        //     std::string their_ip = ip[1-i];
        
        //     networkLayers[i].configure_socket(0, their_ip, their_port, my_port, true);
        //     networkLayers[i].populate_socket_table();
        //     networkLayers[i].print_socket_table(1);
        // }

        // for (auto i=0; i<FPGA_COUNT; ++i) {
        //     networkLayers[i].arp_discovery();
        //     sleep(5);
        // }
        // sleep(5);

        // for (auto i=0; i<FPGA_COUNT; ++i) {
        //     std::map<int, std::pair<std::string, std::string>> arp_table = networkLayers[i].read_arp_table(255);
        //     for (auto entry : arp_table) {
        //         std::cout << "Entry: " << entry.first << " IP: " << entry.second.first << " MAC: " << entry.second.second << std::endl;
        //     }
        // }



    

    }
    sleep(1);



    auto pageRank_0= xrt::ip(fpga_card[0], uuids[0], pr0_name);



    auto pageRank_1 = xrt::ip(fpga_card[1], uuids[1], pr0_name);

  

    std::vector<Memory *> memories_;

    auto memory_0 = XRTMemory(fpga_card[0], pageRank_0);
    auto memory_1 = XRTMemory(fpga_card[1], pageRank_1);

    memories_.push_back(&memory_0);
    memories_.push_back(&memory_1);
    


    pageRankDriver driver(memories_, graph_file);
    driver.run_test_bench_mFpga();

    // for (auto i=0; i<FPGA_COUNT; ++i) {

    //     networkLayers[i].get_udp_in_pkts();
    //     networkLayers[i].get_udp_out_pkts();
    //     networkLayers[i].get_udp_app_in_pkts();
    //     networkLayers[i].get_udp_app_out_pkts();
    //     networkLayers[i].get_icmp_in_pkts();
    //     networkLayers[i].get_icmp_out_pkts();
    // }

    for(int i = 0; i < FPGA_COUNT; i++){
        auto stats = cmacs[i].statistics(true);
        std::cout << "Stat TX CMAC:" << i << std::endl;
        for (auto entry : stats.tx) {
            std::cout << entry.first << ": " << entry.second << std::endl;
        }
        std::cout << "Stat RX CMAC:" << i << std::endl;
        for (auto entry : stats.rx) {
            std::cout << entry.first << ": " << entry.second << std::endl;
        }
        std::cout << "Cycle Count: " << stats.cycle_count << std::endl;
    }

    return 0;
}