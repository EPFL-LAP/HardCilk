#ifndef MFPGA_DRIVER_H
#define MFPGA_DRIVER_H

#include "FullSysGenDescriptor.h"

#include <map>
#include <stdint.h>
#include <stdlib.h>
#include <unistd.h>
#include <vector>
#include <stdexcept>
#include <iostream>
#include <memIO.h>
#include <cstring>
#include <cmath>
#include <hardCilkDriver.h>


class HardCilkDriverNoVirtual : public hardCilkDriver{
    public:
    HardCilkDriverNoVirtual(Memory *memory) : hardCilkDriver(memory) {}
    int run_test_bench() override
    {
        return 0;
    }
};

class mFpgaHardCilkDriver
{

public:
    int virtual run_test_bench_mFpga() = 0;

    mFpgaHardCilkDriver(std::vector<Memory *> memories){
        std::cout << "MEMORIES SIZE: " << memories.size() << std::endl;
        for(auto memory : memories){
            memories_.push_back(memory);
        }
        for(auto memory : memories){
            drivers.push_back(new HardCilkDriverNoVirtual(memory));
        }
    }

    static bool defaultDoneCondition(int32_t val)
    {
        return val < 0;
    }

    template <typename T> 
    int initSystemMfpga(std::vector<T> base_task_data, bool (*conditionMfpga)(int32_t) = defaultDoneCondition){
        // get the system descriptor from any of the drivers
        auto descriptor = drivers[0]->descriptor;

        // loop over the memorties and write the fpga Index and the fpga count in the management registers
        if(drivers.size() > 1){
            for(auto memory = memories_.begin(); memory != memories_.end(); memory++){
                (*memory)->writeReg64(descriptor.getMfpgaBaseAddress() + fpga_count_shift, memories_.size());
                (*memory)->writeReg64(descriptor.getMfpgaBaseAddress() + fpga_id_shift, memory - memories_.begin());
                (*memory)->writeReg64(descriptor.getMfpgaBaseAddress() + tasks_to_move, 128);
            }
        }


        auto driver_0 = drivers[0];
        driver_0->initSystem(base_task_data, conditionMfpga, 0);
        int fpgaId = 1;
        for(auto driver = drivers.begin() + 1; driver != drivers.end(); driver++){
            (*driver)->initSystem(base_task_data, conditionMfpga, fpgaId, 0, true); // no base tasks for the rest of the drivers
            fpgaId++;
        }
        return 0;    
    }


    int startSystemMfpga(){
        
        // Read sent and received messages and assert them to be zero!
        for(auto memory = memories_.begin(); memory != memories_.end(); memory++){
            int index = memory - memories_.begin() ;
            auto messages_recevied =  (*memory)->readReg64(drivers[index]->descriptor.getMfpgaBaseAddress() + messages_receveid_count_shift);
            auto messages_sent = (*memory)->readReg64(drivers[index]->descriptor.getMfpgaBaseAddress() + messages_sent_count_shift);

            std::cout << "FPGA with ID: " << index << " has sent: " <<  messages_sent << ", received: " << messages_recevied << std::endl;
        }




        for(auto driver = drivers.begin(); driver != drivers.end(); driver++){
            (*driver)->startSystem();
        }
        // log T_START
        //std::cout << "T_START" << sc::now() << std::endl;
        return 0;
    }

    void managementLoopMfpga(){
        // Call the management loop of the instance that the return address was allocated at
        while (true)
        {
            int i = 0;
            for(auto driver = drivers.begin(); driver != drivers.end(); driver++){
                // printf("##################################################################################\n");
                // printf("FPGA with ID: %d\n", i);

                auto val =  (*driver)->managementLoop();
                if(val == 0){
                    return;
                }
                i = (i + 1) % drivers.size();
            
            }
        }


        
    }

    uint64_t allocateMemFPGA_Mfpga(uint64_t size, uint64_t alignment, int fpgaIndex = 0){
        return memories_[fpgaIndex]->allocateMemFPGA(size, alignment);
    }



    ~mFpgaHardCilkDriver(){
        for(auto driver = drivers.begin(); driver != drivers.end(); driver++){
            delete *driver;
        }
    }
    
    std::vector<Memory *> memories_;
    std::vector<HardCilkDriverNoVirtual *> drivers;

    const uint8_t fpga_count_shift = 0x0;
    const uint8_t fpga_id_shift = 0x8;
    const uint8_t tasks_to_move = 0x10;   
    const uint16_t messages_receveid_count_shift = 0x18;
    const uint16_t messages_sent_count_shift = 0x20;    
     

};
#endif
