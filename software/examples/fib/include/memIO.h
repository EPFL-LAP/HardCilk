#pragma once
#include <stdlib.h>
#include <memory>
#include <stdint.h>

struct Memory : std::enable_shared_from_this<Memory> {
    virtual void copyToDevice(uint64_t dest_addr, uint8_t const* src, uint32_t size) = 0;
    virtual void copyFromDevice(uint8_t* dest, uint64_t src_addr, uint32_t size) = 0;

    virtual void writeReg32(uint64_t addr, uint32_t value) = 0;
    virtual void writeReg64(uint64_t addr, uint64_t value) = 0;

    virtual uint32_t readReg32(uint64_t addr) = 0;
    virtual uint64_t readReg64(uint64_t addr) = 0;

    std::shared_ptr<Memory> offset(int64_t offset);

    virtual ~Memory() = default;
};

