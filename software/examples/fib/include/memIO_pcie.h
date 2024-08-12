#pragma once

#include <memIO.h>
#include <fmt/format.h>

#include <d4e/interface.h>
#include <d4e/xil.h>

struct PCIeMemory : Memory {
    PCIeMemory() {
        if(d4e_xil_device_open(&xil_device, "/dev/xdma0", 0, 0, MAP_SZ) < 0)
            throw std::runtime_error("Failed to open the device");
    }
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

    ~PCIeMemory() {
        d4e_close(&xil_device.device);
    }
    private:
    struct d4e_xil_device xil_device;
    uint64_t MAP_SZ = 4ULL << 20;
};