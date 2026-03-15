#pragma once

#include <memIO.h>

#include <d4e/interface.h>
#include <d4e/xil.h>
#include <stdexcept>


struct freedMemBlock
{
    uint64_t addr;
    uint64_t size;
};

struct PCIeMemory : Memory {
    PCIeMemory() {
        if(d4e_xil_device_open(&xil_device, "/dev/xdma0", 0, 0, MAP_SZ) < 0)
            throw std::runtime_error("Failed to open the device");
    }

    private:
    struct d4e_xil_device xil_device;
    uint64_t MAP_SZ = 4ULL << 20;
    uint64_t free_mem_base_addr = 0x0;
    std::vector<freedMemBlock> freed_mem_blocks;
    std::vector<std::pair<uint64_t, uint64_t>> trackMalloc;

    public:
    void copyToDevice(uint64_t dest_addr, uint8_t const* src, uint32_t size) {
        d4e_dma_h2d(&xil_device.device, dest_addr, src, size);
    }

    void copyFromDevice(uint8_t* dest, uint64_t src_addr, uint32_t size) {
        d4e_dma_d2h(&xil_device.device, dest, src_addr, size);
    }

    void writeReg32(uint64_t addr, uint32_t value) {
        d4e_reg_write32(&xil_device.device, addr, value);
    }

    void writeReg64(uint64_t addr, uint64_t value) {
        d4e_reg_write64(&xil_device.device, addr, value);
    }

    uint32_t readReg32(uint64_t addr) {
        return d4e_reg_read32(&xil_device.device, addr);
    }
    uint64_t readReg64(uint64_t addr) {
        return d4e_reg_read64(&xil_device.device, addr);
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

    ~PCIeMemory() {
        d4e_close(&xil_device.device);
    }

};