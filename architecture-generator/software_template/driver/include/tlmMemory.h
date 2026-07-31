#pragma once
#include <algorithm>

#include <memIO.h>
#include <systemc>
#include <tlm>
#include <sctlm/tlm_lib/drivers/memory.hpp>
#include <sctlm/tlm_lib/modules/iconnect.hpp>
#include <sctlm/tlm_lib/modules/memory.hpp>


struct TlmMemory : Memory {
    TlmMemory(sctlm::tlm_lib::drivers::memory_interface &mem, sctlm::tlm_lib::drivers::memory_interface &mgmt): mem_driver_(mem), mgmt_driver_(mgmt) {
    }

    // The TLM driver's length argument is narrower than uint64_t, so split large
    // transfers rather than letting the value truncate silently.
    static constexpr uint64_t HC_TLM_CHUNK = 1ULL << 30;   // 1 GiB

    void copyToDevice(uint64_t dest_addr, uint8_t const* src, uint64_t size) override {
        while (size > 0) {
            uint64_t chunk = std::min<uint64_t>(size, HC_TLM_CHUNK);
            mem_driver_.write((sctlm::tlm_lib::drivers::addr_type)dest_addr, chunk, src);
            dest_addr += chunk;
            src       += chunk;
            size      -= chunk;
        }
    }
    void copyFromDevice(uint8_t* dest, uint64_t src_addr, uint64_t size) override {
        while (size > 0) {
            uint64_t chunk = std::min<uint64_t>(size, HC_TLM_CHUNK);
            mem_driver_.read((sctlm::tlm_lib::drivers::addr_type)src_addr, chunk, dest);
            dest     += chunk;
            src_addr += chunk;
            size     -= chunk;
        }
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

private:
    sctlm::tlm_lib::drivers::memory_interface & mem_driver_;
    sctlm::tlm_lib::drivers::memory_interface & mgmt_driver_;
};
