#pragma once
#include <stdlib.h>
#include <memory>
#include <stdint.h>
#include <memIO.h>
#include <vector>
#include <bits/stdc++.h>

#include <xrt/xrt_bo.h>
#include <xrt/xrt_device.h>
#include <experimental/xrt_xclbin.h>
#include <experimental/xrt_ip.h>

// ---------------------------------------------------------------------------
// Two XRT memory backends, selected at construction time.
//
//   XRTBankedMemory  - pre-allocates one large BO per HBM pseudo-channel and
//                      bump-allocates inside them. One BO per bank means very
//                      few XRT objects and no per-allocation driver round trip,
//                      which is what you want on real hardware.
//
//   XRTPagedMemory   - allocates a BO per request, on demand, rounded up to a
//                      page. Nothing is reserved up front. This is the backend
//                      that works under emulation: xsim/QEMU has to model every
//                      byte of a BO, so the banked backend's 16 GiB of
//                      up-front reservation is not viable there.
//
// XRTMemory picks between them: emulation (XCL_EMULATION_MODE set by
// `setenv XCL_EMULATION_MODE=hw_emu` / sw_emu) selects the paged backend,
// anything else selects the banked one. Set HARDCILK_XRT_MEMORY=paged|banked
// to override.
// ---------------------------------------------------------------------------

// Shared helpers: both backends hand out addresses inside BOs and must agree on
// how a device address maps back to (BO, offset).
struct XRTMemoryRegion {
    xrt::bo  bo;
    uint64_t base_addr;   // bo.address()
    uint64_t capacity;    // usable bytes from base_addr
    uint64_t used;        // bump cursor, bytes
};

static inline bool xrtIsPowerOfTwo(uint64_t v) { return v != 0 && (v & (v - 1)) == 0; }

// Rounds addr up to the next multiple of alignment. Returns false on overflow.
static inline bool xrtAlignUp(uint64_t addr, uint64_t alignment, uint64_t* out) {
    uint64_t aligned = (addr + alignment - 1) & ~(alignment - 1);
    if (aligned < addr) return false;
    *out = aligned;
    return true;
}

// ---------------------------------------------------------------------------
// Banked backend: one BO per HBM pseudo-channel, reserved up front.
// ---------------------------------------------------------------------------
struct XRTBankedMemory : Memory {

private:
    std::vector<XRTMemoryRegion> banks_;
    xrt::device                  dev_;
    xrt::ip                      hardCilk_ip_;
    uint64_t                     total_capacity_ = 0;

    XRTMemoryRegion* findBank(uint64_t addr) {
        for (auto& bank : banks_) {
            if (addr >= bank.base_addr && addr < bank.base_addr + bank.capacity)
                return &bank;
        }
        return nullptr;
    }

public:
    // per_bank_bytes: bytes to pre-allocate per HBM pseudo-channel.
    //   Must meet XRT's minimum BO size for the target memory type
    //   (typically >= 4 MB for HBM on Alveo U55C).
    // num_banks: how many pseudo-channels to try (max 32 for U55C).
    XRTBankedMemory(xrt::device& dev, xrt::ip& hardCilk_ip,
                    uint64_t per_bank_bytes = 512ULL * 1024 * 1024,
                    uint32_t num_banks = 32)
        : dev_(dev), hardCilk_ip_(hardCilk_ip)
    {
        banks_.reserve(num_banks);
        // Every bank that fails to allocate is reported, not just swallowed: a
        // bank can be missing from the topology (benign) or fail because the
        // device is out of memory / already claimed by another process (not
        // benign, and otherwise indistinguishable from a smaller board).
        for (uint32_t i = 0; i < num_banks; ++i) {
            try {
                // 4-arg form: explicit flags avoids overload ambiguity and
                // prevents unintended userptr / cacheable BO selection.
                xrt::bo bo(dev_, per_bank_bytes,
                           xrt::bo::flags::normal,
                           static_cast<xrt::memory_group>(i));
                uint64_t base = bo.address();
                banks_.push_back({std::move(bo), base, per_bank_bytes, 0});
                total_capacity_ += per_bank_bytes;
            } catch (const std::exception& e) {
                std::cerr << "XRTBankedMemory: bank " << i << " unavailable ("
                          << (per_bank_bytes >> 20) << " MiB): " << e.what() << "\n";
            }
        }

        if (banks_.empty())
            throw std::runtime_error("XRTBankedMemory: no FPGA memory banks could be allocated");

        std::cerr << "XRTBankedMemory: " << banks_.size() << "/" << num_banks
                  << " banks available, " << (total_capacity_ >> 20)
                  << " MiB total (max single allocation "
                  << (per_bank_bytes >> 20) << " MiB)\n";
        if (banks_.size() < num_banks)
            std::cerr << "XRTBankedMemory: WARNING - " << (num_banks - banks_.size())
                      << " bank(s) missing; usable memory is "
                      << (total_capacity_ >> 20) << " MiB, not "
                      << ((per_bank_bytes * num_banks) >> 20) << " MiB\n";
    }

    size_t   bankCount()     const { return banks_.size(); }
    uint64_t totalCapacity() const { return total_capacity_; }

    void writeReg32(uint64_t addr, uint32_t value) {
        hardCilk_ip_.write_register(addr, value);
    }

    void writeReg64(uint64_t addr, uint64_t value) {
        hardCilk_ip_.write_register(addr,     static_cast<uint32_t>( value        & 0xFFFFFFFF));
        hardCilk_ip_.write_register(addr + 4, static_cast<uint32_t>((value >> 32) & 0xFFFFFFFF));
    }

    uint32_t readReg32(uint64_t addr) {
        return hardCilk_ip_.read_register(addr);
    }

    uint64_t readReg64(uint64_t addr) {
        uint32_t low  = hardCilk_ip_.read_register(addr);
        uint32_t high = hardCilk_ip_.read_register(addr + 4);
        return static_cast<uint64_t>(low) | (static_cast<uint64_t>(high) << 32);
    }

    // Bump-allocates within the first bank that has enough contiguous space.
    // Returns the device address of the allocation. Never returns an address
    // for a region it could not actually reserve: every rejection throws.
    uint64_t allocateMemFPGA(uint64_t size, uint64_t alignment) {
        if (!xrtIsPowerOfTwo(alignment))
            throw std::runtime_error("XRTBankedMemory: alignment " + std::to_string(alignment)
                                     + " is zero or not a power of two");
        if (size == 0)
            throw std::runtime_error("XRTBankedMemory: zero-size allocation requested");

        // A single allocation cannot span banks, so this is the real ceiling
        // regardless of how much aggregate memory is free.
        uint64_t max_bank_capacity = 0;
        for (auto const& bank : banks_)
            max_bank_capacity = std::max(max_bank_capacity, bank.capacity);
        if (size > max_bank_capacity)
            throw std::runtime_error(
                "XRTBankedMemory: allocation of " + std::to_string(size)
                + " bytes exceeds the largest bank (" + std::to_string(max_bank_capacity)
                + " bytes); allocations cannot span banks");

        for (auto& bank : banks_) {
            // Align the absolute device address, not the bank-relative offset:
            // bo.address() is not guaranteed to satisfy the caller's alignment.
            uint64_t addr = bank.base_addr + bank.used;
            if (addr < bank.base_addr) continue;  // wrapped: bank geometry is nonsense
            uint64_t aligned = 0;
            if (!xrtAlignUp(addr, alignment, &aligned)) continue;
            uint64_t offset = aligned - bank.base_addr;

            // Subtractive form: `offset + size <= capacity` can wrap and admit
            // an allocation that does not fit.
            if (offset <= bank.capacity && size <= bank.capacity - offset) {
                bank.used = offset + size;
                return bank.base_addr + offset;
            }
        }

        uint64_t free_bytes = 0;
        for (auto const& bank : banks_)
            free_bytes += bank.capacity - bank.used;
        throw std::runtime_error(
            "XRTBankedMemory: out of FPGA memory - request " + std::to_string(size)
            + " bytes (alignment " + std::to_string(alignment) + ") did not fit in any of "
            + std::to_string(banks_.size()) + " bank(s); "
            + std::to_string(free_bytes) + " bytes free in aggregate but fragmented across banks");
    }

    // Copies [src, src+size) to device address dest_addr.
    void copyToDevice(uint64_t dest_addr, uint8_t const* src, uint64_t size) {
        uint64_t       left = size;
        uint64_t       addr = dest_addr;
        const uint8_t* ptr  = src;

        while (left > 0) {
            XRTMemoryRegion* bank = findBank(addr);
            if (!bank)
                throw std::runtime_error("copyToDevice: address not mapped to any bank");

            uint64_t offset = addr - bank->base_addr;
            uint64_t chunk  = std::min<uint64_t>(left, bank->capacity - offset);

            uint8_t* host_ptr = bank->bo.map<uint8_t*>();
            if (!host_ptr)
                throw std::runtime_error("copyToDevice: failed to map BO");

            std::copy(ptr, ptr + chunk, host_ptr + offset);
            bank->bo.sync(XCL_BO_SYNC_BO_TO_DEVICE, chunk, offset);

            addr += chunk;
            ptr  += chunk;
            left -= chunk;
        }
    }

    // Copies size bytes from device address src_addr into dest.
    void copyFromDevice(uint8_t* dest, uint64_t src_addr, uint64_t size) {
        uint64_t  left = size;
        uint64_t  addr = src_addr;
        uint8_t*  ptr  = dest;

        while (left > 0) {
            XRTMemoryRegion* bank = findBank(addr);
            if (!bank)
                throw std::runtime_error("copyFromDevice: address not mapped to any bank");

            uint64_t offset = addr - bank->base_addr;
            uint64_t chunk  = std::min<uint64_t>(left, bank->capacity - offset);

            bank->bo.sync(XCL_BO_SYNC_BO_FROM_DEVICE, chunk, offset);

            uint8_t* host_ptr = bank->bo.map<uint8_t*>();
            if (!host_ptr)
                throw std::runtime_error("copyFromDevice: failed to map BO");

            std::copy(host_ptr + offset, host_ptr + offset + chunk, ptr);

            addr += chunk;
            ptr  += chunk;
            left -= chunk;
        }
    }

    ~XRTBankedMemory() {}
};

// ---------------------------------------------------------------------------
// Paged backend: one BO per allocation, created on demand.
//
// This is the emulation-friendly path. Nothing is reserved up front, so the
// simulator only ever models the bytes the design actually uses. Each
// allocation is a single BO, so the region it returns is contiguous by
// construction -- the earlier fixed-4-KiB-page version handed back the address
// of the first page and *assumed* subsequent pages landed adjacent to it, which
// silently produced a scrambled buffer whenever XRT chose otherwise.
// ---------------------------------------------------------------------------
struct XRTPagedMemory : Memory {

private:
    static constexpr uint64_t PAGE_BYTES = 4096ULL * 4;   // 16 KiB granularity

    std::vector<XRTMemoryRegion> regions_;   // one per allocation
    std::vector<uint64_t>        bank_used_; // bytes handed out per bank
    xrt::device                  dev_;
    xrt::ip                      hardCilk_ip_;
    uint64_t                     bank_capacity_;
    uint32_t                     num_banks_;
    uint64_t                     total_allocated_ = 0;

    XRTMemoryRegion* findRegion(uint64_t addr) {
        for (auto& r : regions_) {
            if (addr >= r.base_addr && addr < r.base_addr + r.capacity)
                return &r;
        }
        return nullptr;
    }

public:
    XRTPagedMemory(xrt::device& dev, xrt::ip& hardCilk_ip,
                   uint64_t bank_capacity = 512ULL * 1024 * 1024,
                   uint32_t num_banks = 32)
        : bank_used_(num_banks, 0), dev_(dev), hardCilk_ip_(hardCilk_ip),
          bank_capacity_(bank_capacity), num_banks_(num_banks)
    {
        if (num_banks_ == 0)
            throw std::runtime_error("XRTPagedMemory: num_banks must be non-zero");
        std::cerr << "XRTPagedMemory: on-demand allocation, budget "
                  << num_banks_ << " x " << (bank_capacity_ >> 20) << " MiB\n";
    }

    uint64_t totalAllocated() const { return total_allocated_; }

    void writeReg32(uint64_t addr, uint32_t value) {
        hardCilk_ip_.write_register(addr, value);
    }

    void writeReg64(uint64_t addr, uint64_t value) {
        hardCilk_ip_.write_register(addr,     static_cast<uint32_t>( value        & 0xFFFFFFFF));
        hardCilk_ip_.write_register(addr + 4, static_cast<uint32_t>((value >> 32) & 0xFFFFFFFF));
    }

    uint32_t readReg32(uint64_t addr) {
        return hardCilk_ip_.read_register(addr);
    }

    uint64_t readReg64(uint64_t addr) {
        uint32_t low  = hardCilk_ip_.read_register(addr);
        uint32_t high = hardCilk_ip_.read_register(addr + 4);
        return static_cast<uint64_t>(low) | (static_cast<uint64_t>(high) << 32);
    }

    uint64_t allocateMemFPGA(uint64_t size, uint64_t alignment) {
        if (!xrtIsPowerOfTwo(alignment))
            throw std::runtime_error("XRTPagedMemory: alignment " + std::to_string(alignment)
                                     + " is zero or not a power of two");
        if (size == 0)
            throw std::runtime_error("XRTPagedMemory: zero-size allocation requested");

        // Over-allocate by (alignment - 1) so the requested alignment can always
        // be satisfied inside the BO, whatever address XRT hands back, then round
        // the whole thing up to a page.
        uint64_t needed = size + (alignment - 1);
        if (needed < size)
            throw std::runtime_error("XRTPagedMemory: allocation size overflows with alignment");
        uint64_t bo_bytes = 0;
        if (!xrtAlignUp(needed, PAGE_BYTES, &bo_bytes))
            throw std::runtime_error("XRTPagedMemory: allocation size overflows page rounding");

        std::string failures;
        for (uint32_t bank = 0; bank < num_banks_; ++bank) {
            if (bank_used_[bank] + bo_bytes > bank_capacity_)
                continue;   // over this bank's budget

            try {
                xrt::bo bo(dev_, bo_bytes,
                           xrt::bo::flags::normal,
                           static_cast<xrt::memory_group>(bank));
                uint64_t base = bo.address();

                uint64_t aligned = 0;
                if (!xrtAlignUp(base, alignment, &aligned) ||
                    aligned - base + size > bo_bytes) {
                    // Cannot happen with the padding above, but never hand back
                    // an address whose alignment was not actually achieved.
                    throw std::runtime_error("alignment could not be satisfied within the BO");
                }

                regions_.push_back({std::move(bo), base, bo_bytes, bo_bytes});
                bank_used_[bank] += bo_bytes;
                total_allocated_ += bo_bytes;
                return aligned;
            } catch (const std::exception& e) {
                failures += "\n  bank " + std::to_string(bank) + ": " + e.what();
            }
        }

        throw std::runtime_error(
            "XRTPagedMemory: could not allocate " + std::to_string(size)
            + " bytes (alignment " + std::to_string(alignment) + ", BO size "
            + std::to_string(bo_bytes) + ") in any of " + std::to_string(num_banks_)
            + " bank(s); " + std::to_string(total_allocated_)
            + " bytes already allocated." + failures);
    }

    void copyToDevice(uint64_t dest_addr, uint8_t const* src, uint64_t size) {
        uint64_t       left = size;
        uint64_t       addr = dest_addr;
        const uint8_t* ptr  = src;

        while (left > 0) {
            XRTMemoryRegion* r = findRegion(addr);
            if (!r)
                throw std::runtime_error("copyToDevice: address 0x" + std::to_string(addr)
                                         + " is not inside any allocation");

            uint64_t offset = addr - r->base_addr;
            uint64_t chunk  = std::min<uint64_t>(left, r->capacity - offset);

            uint8_t* host_ptr = r->bo.map<uint8_t*>();
            if (!host_ptr)
                throw std::runtime_error("copyToDevice: failed to map BO");

            std::copy(ptr, ptr + chunk, host_ptr + offset);
            r->bo.sync(XCL_BO_SYNC_BO_TO_DEVICE, chunk, offset);

            addr += chunk;
            ptr  += chunk;
            left -= chunk;
        }
    }

    void copyFromDevice(uint8_t* dest, uint64_t src_addr, uint64_t size) {
        uint64_t  left = size;
        uint64_t  addr = src_addr;
        uint8_t*  ptr  = dest;

        while (left > 0) {
            XRTMemoryRegion* r = findRegion(addr);
            if (!r)
                throw std::runtime_error("copyFromDevice: address 0x" + std::to_string(addr)
                                         + " is not inside any allocation");

            uint64_t offset = addr - r->base_addr;
            uint64_t chunk  = std::min<uint64_t>(left, r->capacity - offset);

            r->bo.sync(XCL_BO_SYNC_BO_FROM_DEVICE, chunk, offset);

            uint8_t* host_ptr = r->bo.map<uint8_t*>();
            if (!host_ptr)
                throw std::runtime_error("copyFromDevice: failed to map BO");

            std::copy(host_ptr + offset, host_ptr + offset + chunk, ptr);

            addr += chunk;
            ptr  += chunk;
            left -= chunk;
        }
    }

    ~XRTPagedMemory() {}
};

// ---------------------------------------------------------------------------
// Front end. Keeps the original XRTMemory(dev, ip) construction working and
// forwards to whichever backend suits the run.
// ---------------------------------------------------------------------------
struct XRTMemory : Memory {

private:
    std::unique_ptr<Memory> impl_;
    bool                    emulation_ = false;

    // hw_emu / sw_emu is signalled by XCL_EMULATION_MODE; HARDCILK_XRT_MEMORY
    // overrides the choice for debugging (paged on hardware, banked in emu).
    static bool selectPaged(bool* emulation_out) {
        const char* emu = getenv("XCL_EMULATION_MODE");
        bool emulation = (emu != nullptr && *emu != '\0');
        *emulation_out = emulation;

        const char* override_mode = getenv("HARDCILK_XRT_MEMORY");
        if (override_mode) {
            std::string mode(override_mode);
            if (mode == "paged")  return true;
            if (mode == "banked") return false;
            throw std::runtime_error("HARDCILK_XRT_MEMORY must be \"paged\" or \"banked\", got \""
                                     + mode + "\"");
        }
        return emulation;
    }

public:
    XRTMemory(xrt::device& dev, xrt::ip& hardCilk_ip,
              uint64_t per_bank_bytes = 512ULL * 1024 * 1024,
              uint32_t num_banks = 32)
    {
        bool paged = selectPaged(&emulation_);
        const char* emu = getenv("XCL_EMULATION_MODE");
        std::cerr << "XRTMemory: XCL_EMULATION_MODE=" << (emu ? emu : "<unset>")
                  << " -> using the " << (paged ? "paged (on-demand)" : "banked (pre-reserved)")
                  << " backend\n";

        if (paged)
            impl_ = std::make_unique<XRTPagedMemory>(dev, hardCilk_ip, per_bank_bytes, num_banks);
        else
            impl_ = std::make_unique<XRTBankedMemory>(dev, hardCilk_ip, per_bank_bytes, num_banks);
    }

    bool isEmulation() const { return emulation_; }

    void     writeReg32(uint64_t addr, uint32_t value) override { impl_->writeReg32(addr, value); }
    void     writeReg64(uint64_t addr, uint64_t value) override { impl_->writeReg64(addr, value); }
    uint32_t readReg32(uint64_t addr) override                  { return impl_->readReg32(addr); }
    uint64_t readReg64(uint64_t addr) override                  { return impl_->readReg64(addr); }

    uint64_t allocateMemFPGA(uint64_t size, uint64_t alignment) override {
        return impl_->allocateMemFPGA(size, alignment);
    }

    void copyToDevice(uint64_t dest_addr, uint8_t const* src, uint64_t size) override {
        impl_->copyToDevice(dest_addr, src, size);
    }

    void copyFromDevice(uint8_t* dest, uint64_t src_addr, uint64_t size) override {
        impl_->copyFromDevice(dest, src_addr, size);
    }

    ~XRTMemory() {}
};
