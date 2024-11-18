#pragma once

#include <memIO.h>
#include <sc_dpiheader_questa.h>

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

        // Check if the size is less than 32 bytes, if not divide the data into 32 byte chunks or less and send them
        if (size <= 32) {
            S_AXI_WRITE_MEM(
                dest_addr,
                reinterpret_cast<svBitVecVal*>(data_tmp),
                size);
        } else {
            uint32_t offset = 0;
            while (offset < size) {
                int current_size = 32;
                if (size - offset < 32) {
                    current_size = size - offset;
                }
                S_AXI_WRITE_MEM(
                    dest_addr + offset,
                    reinterpret_cast<svBitVecVal*>(data_tmp + offset),
                    current_size);
                offset += 32;
            }
        }
    }

    void copyFromDevice(uint8_t* dest, uint64_t src_addr, uint32_t size) {
        #ifdef MTI_SYSTEMC
            svSetScope(svGetScopeFromName("TestBench.myModule"));
        #endif  

        // Create a local buffer to hold the data
        uint8_t * data = new uint8_t[size + 512];




        // Check if the size is less than 32 bytes, if not divide the data into 32 byte chunks or less and send them
        if (size <= 32) {
            S_AXI_READ_MEM(
                src_addr,
                reinterpret_cast<svBitVecVal*>(data),
                size);
        } else {
            uint32_t offset = 0;
            while (offset < size) {
                int current_size = 32;
                if (size - offset < 32) {
                    current_size = size - offset;
                }
                S_AXI_READ_MEM(
                    src_addr + offset,
                    reinterpret_cast<svBitVecVal*>(data + offset),
                    current_size);
                offset += 32;
            }
        }

        // Copy the data from the local buffer
        memcpy(dest, data, size);
    }

    ~questaMemory() {

    }
};