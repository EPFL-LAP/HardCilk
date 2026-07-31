#pragma once
#include <stdlib.h>
#include <memory>
#include <stdint.h>

// This is used to track memory freed by the processor to extend one of the FPGA
// queues to another location. Defined here because both the driver and every
// Memory backend need it, and a translation unit routinely includes both.
struct freedMemBlock
{
    uint64_t addr;
    uint64_t size;
};

struct Memory : std::enable_shared_from_this<Memory> {
    virtual void copyToDevice(uint64_t dest_addr, uint8_t const* src, uint64_t size) = 0;
    virtual void copyFromDevice(uint8_t* dest, uint64_t src_addr, uint64_t size) = 0;

    virtual void writeReg32(uint64_t addr, uint32_t value) = 0;
    virtual void writeReg64(uint64_t addr, uint64_t value) = 0;

    virtual uint32_t readReg32(uint64_t addr) = 0;
    virtual uint64_t readReg64(uint64_t addr) = 0;

    virtual uint64_t allocateMemFPGA(uint64_t size, uint64_t alignment) = 0;

    std::shared_ptr<Memory> offset(int64_t offset);

    virtual ~Memory() = default;
};

