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

    template <typename T> 
    int initSystemMfpga(std::vector<T> base_task_data){
        // get the system descriptor from any of the drivers
        auto descriptor = drivers[0]->descriptor;

        // loop over the memorties and write the fpga Index and the fpga count in the management registers
        for(auto memory = memories_.begin(); memory != memories_.end(); memory++){
            (*memory)->writeReg64(descriptor.getFpgaIdAddress() + fpga_count_shift, memories_.size());
            (*memory)->writeReg64(descriptor.getFpgaIdAddress() + fpga_id_shift, memory - memories_.begin());
        }


        auto driver_0 = drivers[0];
        driver_0->initSystem(base_task_data);
        for(auto driver = drivers.begin() + 1; driver != drivers.end(); driver++){
            (*driver)->initSystem(base_task_data, true); // no base tasks for the rest of the drivers
        }
        return 0;    
    }


    int startSystemMfpga(){
        for(auto driver = drivers.begin(); driver != drivers.end(); driver++){
            (*driver)->startSystem();
        }
        // log T_START
        //std::cout << "T_START" << sc::now() << std::endl;
        return 0;
    }

    void managementLoopMfpga(){
        while (true){
             //wait(1);
        }
    }

    uint64_t allocateMemFPGA_Mfpga(uint64_t size, uint64_t alignment, int fpgaIndex = 0){
        HardCilkDriverNoVirtual * driver = drivers[fpgaIndex]; 
        return driver->allocateMemFPGA(size, alignment);
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
    

};
#endif
