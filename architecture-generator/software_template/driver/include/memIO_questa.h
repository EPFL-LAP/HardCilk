#pragma once

#include <memIO.h>
#include <sc_dpiheader_questa.h>
#include <vector>
#include <utility>

struct questaMemory : Memory {
    questaMemory() {

    }

    uint32_t readReg32(uint64_t addr) {

        #ifdef MTI_SYSTEMC
            svSetScope(svGetScopeFromName("TestBench.myModule"));
        #endif  

        uint8_t data[512];
        S_AXI_READ_REG(
            addr,
            reinterpret_cast<svBitVecVal*>(data),
            4);
        return *(reinterpret_cast<uint32_t*>(data));
    }

    uint64_t readReg64(uint64_t addr) {

        #ifdef MTI_SYSTEMC
            svSetScope(svGetScopeFromName("TestBench.myModule"));
        #endif  

        uint8_t data[512];
        S_AXI_READ_REG(
            addr,
            reinterpret_cast<svBitVecVal*>(data),
            8);
        return *(reinterpret_cast<uint64_t*>(data));
    }

    void writeReg32(uint64_t addr, uint32_t value) {
        #ifdef MTI_SYSTEMC
            svSetScope(svGetScopeFromName("TestBench.myModule"));
        #endif  

        uint8_t data[512];
        *(reinterpret_cast<uint32_t*>(data)) = value;
        S_AXI_WRITE_REG(
            addr,
            reinterpret_cast<svBitVecVal*>(data),
            4);
    }

    void writeReg64(uint64_t addr, uint64_t value) {
        #ifdef MTI_SYSTEMC
            svSetScope(svGetScopeFromName("TestBench.myModule"));
        #endif  

        uint8_t data[512];
        *(reinterpret_cast<uint64_t*>(data)) = value;
        S_AXI_WRITE_REG(
            addr,
            reinterpret_cast<svBitVecVal*>(data),
            8);
    }

    void copyToDevice(uint64_t dest_addr, uint8_t const* src, uint32_t size) {
        #ifdef MTI_SYSTEMC
            svSetScope(svGetScopeFromName("TestBench.myModule"));
        #endif  

        // Create a local buffer to hold the data
        uint8_t * data_tmp = new uint8_t[size + 512];

        // Copy the data to the local buffer
        memcpy(data_tmp, src, size);

        // Check if the size crosses 4KB boundary, if so, divide the data into 4KB chunks or less and send them
        uint64_t page_of_start_addr = dest_addr & 0xFFFFFFFFFFFFF000;
        uint64_t page_of_end_addr = (dest_addr + size) & 0xFFFFFFFFFFFFF000;

        std::vector<std::tuple<uint64_t, uint32_t, uint8_t *>> transactions;

        if(page_of_start_addr != page_of_end_addr) {
            // Get a transaction up to the 4KB boundary
            uint32_t size_to_boundary = 4096 - (dest_addr & 0xFFF);
            transactions.push_back(std::make_tuple(dest_addr, size_to_boundary, data_tmp));
            // Divide the rest of the transactions into 4KB chunks or less
            uint64_t current_addr = dest_addr + size_to_boundary;
            while(current_addr < dest_addr + size) {
                uint32_t current_size = 4096;
                if(dest_addr + size - current_addr < 4096) {
                    current_size = dest_addr + size - current_addr;
                }
                transactions.push_back(std::make_tuple(current_addr, current_size, data_tmp + current_addr - dest_addr));
                current_addr += 4096;
            }
        } else {
            transactions.push_back(std::make_tuple(dest_addr, size, data_tmp));
        }


        for(auto transaction : transactions) {
            // Check if the size is less than 32 bytes, if not divide the data into 32 byte chunks or less and send them
            if(std::get<1>(transaction) <= 32) {
                S_AXI_WRITE_MEM(
                    std::get<0>(transaction),
                    reinterpret_cast<svBitVecVal*>(std::get<2>(transaction)),
                    std::get<1>(transaction));
            } else {
                uint32_t offset = 0;
                while (offset < std::get<1>(transaction)) {
                    int current_size = 32;
                    if (std::get<1>(transaction) - offset < 32) {
                        current_size = std::get<1>(transaction) - offset;
                    }
                    S_AXI_WRITE_MEM(
                        std::get<0>(transaction) + offset,
                        reinterpret_cast<svBitVecVal*>(std::get<2>(transaction) + offset),
                        current_size);
                    offset += 32;
                }
            }
        }
    }

    void copyFromDevice(uint8_t* dest, uint64_t src_addr, uint32_t size) {
        #ifdef MTI_SYSTEMC
            svSetScope(svGetScopeFromName("TestBench.myModule"));
        #endif  

        // Create a local buffer to hold the data
        uint8_t * data = new uint8_t[size + 512];


        // Check if the size crosses 4KB boundary, if so, divide the data into 4KB chunks or less and receieve the data
        uint64_t page_of_start_addr = src_addr & 0xFFFFFFFFFFFFF000;
        uint64_t page_of_end_addr = (src_addr + size) & 0xFFFFFFFFFFFFF000;

        std::vector<std::tuple<uint64_t, uint32_t, uint8_t *>> transactions;

        if(page_of_start_addr != page_of_end_addr) {
            // Get a transaction up to the 4KB boundary
            uint32_t size_to_boundary = 4096 - (src_addr & 0xFFF);
            transactions.push_back(std::make_tuple(src_addr, size_to_boundary, data));
            // Divide the rest of the transactions into 4KB chunks or less
            uint64_t current_addr = src_addr + size_to_boundary;
            while(current_addr < src_addr + size) {
                uint32_t current_size = 4096;
                if(src_addr + size - current_addr < 4096) {
                    current_size = src_addr + size - current_addr;
                }
                transactions.push_back(std::make_tuple(current_addr, current_size, data + current_addr - src_addr));
                current_addr += 4096;
            }
        } else {
            transactions.push_back(std::make_tuple(src_addr, size, data));
        }

        for(auto transaction : transactions) {
            // Check if the size is less than 32 bytes, if not divide the data into 32 byte chunks or less and receive them
            if(std::get<1>(transaction) <= 32) {
                S_AXI_READ_MEM(
                    std::get<0>(transaction),
                    reinterpret_cast<svBitVecVal*>(std::get<2>(transaction)),
                    std::get<1>(transaction));
            } else {
                uint32_t offset = 0;
                while (offset < std::get<1>(transaction)) {
                    int current_size = 32;
                    if (std::get<1>(transaction) - offset < 32) {
                        current_size = std::get<1>(transaction) - offset;
                    }
                    S_AXI_READ_MEM(
                        std::get<0>(transaction) + offset,
                        reinterpret_cast<svBitVecVal*>(std::get<2>(transaction) + offset),
                        current_size);
                    offset += 32;
                }
            }
        }

        // Copy the data from the local buffer
        memcpy(dest, data, size);
    }

    ~questaMemory() {

    }
};