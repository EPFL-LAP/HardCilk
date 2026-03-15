#pragma once

#include <memIO.h>
#include <systemc>
#include <tlm>
#include <sctlm/tlm_lib/drivers/memory.hpp>
#include <sctlm/tlm_lib/modules/iconnect.hpp>
#include <sctlm/tlm_lib/modules/memory.hpp>



struct freedMemBlock
{
    uint64_t addr;
    uint64_t size;
};



struct TlmMemory : Memory {
    TlmMemory(sctlm::tlm_lib::drivers::memory_interface &mem, sctlm::tlm_lib::drivers::memory_interface &mgmt): mem_driver_(mem), mgmt_driver_(mgmt) {
    }

    private:
    sctlm::tlm_lib::drivers::memory_interface & mem_driver_;
    sctlm::tlm_lib::drivers::memory_interface & mgmt_driver_;
    uint64_t free_mem_base_addr = 0x0;
    std::vector<freedMemBlock> freed_mem_blocks;
    std::vector<std::pair<uint64_t, uint64_t>> trackMalloc;


    public:

    void copyToDevice(uint64_t dest_addr, uint8_t const* src, uint32_t size) override {
        mem_driver_.write((sctlm::tlm_lib::drivers::addr_type)dest_addr, size, src);
    }
    void copyFromDevice(uint8_t* dest, uint64_t src_addr, uint32_t size) override {
        mem_driver_.read((sctlm::tlm_lib::drivers::addr_type)src_addr, size, dest);
    }
    void writeReg32(uint64_t addr, uint32_t value) override {
        mgmt_driver_.write32(addr, value);
    }
    void writeReg64(uint64_t addr, uint64_t value) override {
        mgmt_driver_.write64(addr, value);
    }

    uint32_t readReg32(uint64_t addr) override {
        return mgmt_driver_.read32(addr);
    }
    uint64_t readReg64(uint64_t addr) override {
        return mgmt_driver_.read64(addr);
    
    }

    uint64_t allocateMemFPGA(uint64_t size, uint64_t alignment /** alignment is a byte value */)
    {

        // Check if any memory freed by the processor can be used, if yes return and remove it from the freed memory
        for (auto it = freed_mem_blocks.begin(); it != freed_mem_blocks.end(); it++)
        {
            if (it->size >= size)
            {

                auto reminder = it->addr % alignment;
                auto offset = (reminder == 0) ? 0 : alignment - reminder;

                if (it->size - offset < size)
                {
                    continue;
                }

                uint64_t addr = it->addr + offset;
                it->addr += (size + offset);
                it->size -= (size + offset);
                if (it->size == 0)
                {
                    freed_mem_blocks.erase(it);
                }
                return addr;
            }
        }
        // If no memory is freed by the processor, allocate new memory
        // Align the memory to the alignment
        auto reminder = free_mem_base_addr % alignment;
        auto offset = (reminder == 0) ? 0 : alignment - reminder;
        free_mem_base_addr += offset;
        uint64_t addr = free_mem_base_addr;
        free_mem_base_addr += size;
        assert(free_mem_base_addr < 0x3FFFFFFFF);

        for (auto pair : trackMalloc)
        {
            if (addr >= pair.first && addr < pair.second)
            {
                throw("invalid allocation");
            }
        }
        trackMalloc.push_back(std::pair<uint64_t, uint64_t>(addr, addr + size));

        return addr;
    }


};
