#pragma once
#include <memIO.h>
#include <rama_striping.h>

namespace hardcilk_interrupt
{
bool stopRequested();
}

#include <algorithm>
#include <bits/stdc++.h>
#include <chrono>
#include <cstring>
#include <cstdint>
#include <memory>
#include <thread>
#include <stdexcept>
#include <stdlib.h>
#include <iostream>
#include <vector>

// Under the QuestaSim/SystemC (sccom) build the XRT runtime is unavailable, so
// the real XRTMemory (and the xrt headers it needs) are compiled out and a dead
// stub is provided instead (see the #else branch at the bottom of this file).
// The normal XRT/HW build (MTI_SYSTEMC undefined) is byte-for-byte unchanged.
#ifndef MTI_SYSTEMC

#include <xrt/xrt_bo.h>
#include <xrt/xrt_device.h>
#include <experimental/xrt_xclbin.h>
#include <experimental/xrt_ip.h>

struct XRTMemory : Memory{

    private:
    static constexpr uint64_t PAGE_SIZE = 4096 * 16;
    static constexpr uint64_t BANK_SIZE = 512ULL * 1024 * 1024;
    static constexpr int NUM_BANKS = 32;

    struct BufferInfo {
      xrt::bo buffer;
      uint64_t size;
      int bank_index;
    };

    // Base device address -> BO. A single logical allocation may be backed by
    // multiple BOs, but only when their device addresses are exactly adjacent.
    std::map<uint64_t, BufferInfo> addressBufferMap;
    std::vector<uint64_t> availableBytes;
    xrt::device dev_;
    xrt::ip hardCilk_ip_;
    int defaultFirstBank_ = 0;
    int defaultLastBank_ = NUM_BANKS - 1;
    bool warnedBankRangeWidened_ = false;
    RamaHostMapping ramaMapping_;


    public:
    XRTMemory(xrt::device &dev, xrt::ip &hardCilk_ip,
              const std::string &xclbin_path = std::string())
        : availableBytes(NUM_BANKS){
      std::fill(availableBytes.begin(), availableBytes.end(), BANK_SIZE);
      dev_ = dev;
      hardCilk_ip_ = hardCilk_ip;
      const std::string runtime = isEmulation() ? "hw_emu" : "hw";
      ramaMapping_ = loadRamaHostMapping(xclbin_path, runtime);
      if (ramaMapping_.enabled()) {
        if (ramaMapping_.bytes_per_bank != BANK_SIZE ||
            ramaMapping_.first_bank + ramaMapping_.memory_count > NUM_BANKS) {
          throw std::runtime_error("RAMA host mapping is incompatible with this XRTMemory HBM geometry");
        }
      }
      logRamaHostMapping(ramaMapping_, runtime);
    }

    bool isEmulation() const {
      const char *emu_mode = std::getenv("XCL_EMULATION_MODE");
      return emu_mode != nullptr && *emu_mode != '\0';
    }

    void writeReg32(uint64_t addr, uint32_t value){
      retryRegAccess("writeReg32", addr, [&]{
        hardCilk_ip_.write_register(addr, value);
        return 0;
      });
    }

    void writeReg64(uint64_t addr, uint64_t value){
      retryRegAccess("writeReg64.lo", addr, [&]{
        hardCilk_ip_.write_register(addr, static_cast<uint32_t>(value & 0xFFFFFFFF));
        return 0;
      });
      retryRegAccess("writeReg64.hi", addr + 4, [&]{
        hardCilk_ip_.write_register(addr + 4, static_cast<uint32_t>((value >> 32) & 0xFFFFFFFF));
        return 0;
      });
    }

    uint32_t readReg32(uint64_t addr){
      return retryRegAccess("readReg32", addr, [&]{
        return hardCilk_ip_.read_register(addr);
      });
    }

    uint64_t readReg64(uint64_t addr){
      uint32_t low = retryRegAccess("readReg64.lo", addr, [&]{
        return hardCilk_ip_.read_register(addr);
      });
      uint32_t high = retryRegAccess("readReg64.hi", addr + 4, [&]{
        return hardCilk_ip_.read_register(addr + 4);
      });
      u_int64_t value = static_cast<uint64_t>(low) | (static_cast<uint64_t>(high) << 32);
      return value;
    }

    uint64_t allocateMemFPGA(uint64_t size, uint64_t alignment){
      if (size == 0) {
        throw std::runtime_error("Cannot allocate zero bytes");
      }

      uint64_t alloc_alignment = std::max<uint64_t>(alignment, PAGE_SIZE);
      uint64_t aligned_size = alignUp(size, alloc_alignment);

      if (ramaMapping_.enabled() &&
          defaultFirstBank_ <= static_cast<int>(ramaMapping_.first_bank) &&
          defaultLastBank_ >= static_cast<int>(ramaMapping_.first_bank +
                                               ramaMapping_.memory_count - 1)) {
        return allocateStripedBoSpan(aligned_size, alignment);
      }

      if (aligned_size <= BANK_SIZE) {
        return allocateSingleBoInBankRange(aligned_size, alignment,
                                           defaultFirstBank_, defaultLastBank_);
      }

      return allocateContiguousBoSpanInBankRange(aligned_size, alignment,
                                                 defaultFirstBank_, defaultLastBank_);
    }

    void setDefaultBankRange(int firstBank, int lastBank) {
      if (firstBank < 0 || lastBank >= NUM_BANKS || firstBank > lastBank) {
        throw std::runtime_error("setDefaultBankRange: invalid bank range");
      }

      // Never narrow the default range below the RAMA stripe span.
      //
      // allocateMemFPGA() only takes the striped path when the default range
      // COVERS the whole stripe span. A caller that narrows the range -- e.g. a
      // benchmark pinning compute to banks 0..15 while RAMA stripes over 0..31 --
      // silently flips those allocations to a LINEAR allocation, and the address
      // it returns still lies inside the striped window. copyToDevice then sees
      // an address the mapping "contains", takes the STRIPED path, and looks up
      // physical addresses that were never allocated: allocate linear, write
      // striped, "Missing buffer for device address" at the first big write.
      //
      // Under striping the narrowing is meaningless anyway -- every allocation
      // is spread across every bank in the span by construction -- so widening
      // back to the span costs nothing and keeps allocation and access on the
      // same path.
      if (ramaMapping_.enabled()) {
        const int stripeFirst = static_cast<int>(ramaMapping_.first_bank);
        const int stripeLast = static_cast<int>(ramaMapping_.first_bank +
                                                ramaMapping_.memory_count - 1);
        if (firstBank > stripeFirst || lastBank < stripeLast) {
          if (!warnedBankRangeWidened_) {
            warnedBankRangeWidened_ = true;
            std::cout << "[RAMA host] default bank range " << firstBank << ".."
                      << lastBank << " would disable striped allocation; widened to "
                      << stripeFirst << ".." << stripeLast
                      << " (every allocation is striped across the span)\n";
          }
          firstBank = std::min(firstBank, stripeFirst);
          lastBank = std::max(lastBank, stripeLast);
        }
      }

      defaultFirstBank_ = firstBank;
      defaultLastBank_ = lastBank;
    }

    // Clear complete HBM banks without consuming them from this allocator. Each
    // temporary BO is released before the next bank is opened, keeping host
    // memory use bounded while still overwriting every device byte.
    // Physically zero whole HBM banks so a fresh run never reads stale data left
    // by a previous run (xrt-smi reset does NOT clear HBM). The zeroing BOs are
    // temporary and freed before returning, so they do not consume the bank for
    // the real allocations that follow -- the zeros persist in physical HBM.
    //
    // IMPORTANT: do NOT allocate one full-bank (512 MB) BO and write across it.
    // A single large `normal` BO's host mapping is not reliably backed at high
    // offsets on this platform, so writing past the first ~tens of MB faults with
    // SIGBUS ("Bus error"). Instead tile the bank with several small BOs (the same
    // 64 MB size the telemetry path writes successfully), each written only at
    // offset 0. Holding them alive together forces XRT to place them at distinct,
    // adjacent device offsets so the whole bank is covered; they are released at
    // the end of each bank iteration.
    void clearHBMBankRange(int firstBank, int lastBank) {
      if (firstBank < 0 || lastBank >= NUM_BANKS || firstBank > lastBank) {
        throw std::runtime_error("clearHBMBankRange: invalid bank range");
      }

      static constexpr uint64_t ZERO_CHUNK_BYTES = 64ULL * 1024 * 1024;
      static const std::vector<uint8_t> zeros(ZERO_CHUNK_BYTES, 0);
      for (int bank = firstBank; bank <= lastBank; ++bank) {
        // Cooperative stop point. Under hw_emu this clear is minutes long and is
        // the phase a Ctrl-C is most likely to land in; without this the run only
        // notices once it reaches its poll loop. A partial clear is fine -- the
        // run is being abandoned, and the poll loop aborts immediately after.
        if (hardcilk_interrupt::stopRequested()) {
          std::cerr << "[hbm] interrupted by user; stopping HBM clear at bank "
                    << bank << "\n";
          return;
        }
        std::vector<xrt::bo> tiles;
        tiles.reserve(static_cast<size_t>(BANK_SIZE / ZERO_CHUNK_BYTES) + 1);
        for (uint64_t offset = 0; offset < BANK_SIZE;
             offset += ZERO_CHUNK_BYTES) {
          uint64_t chunk = std::min<uint64_t>(ZERO_CHUNK_BYTES,
                                              BANK_SIZE - offset);
          xrt::bo tile(dev_, static_cast<size_t>(chunk),
                       xrt::bo::flags::normal, bank);
          tile.write(zeros.data(), static_cast<size_t>(chunk), 0);
          tile.sync(XCL_BO_SYNC_BO_TO_DEVICE, static_cast<size_t>(chunk), 0);
          tiles.push_back(std::move(tile));
        }
        tiles.clear(); // free this bank's tiles; physical zeros remain in HBM
      }
    }

    uint64_t allocateMemFPGAInBankRange(uint64_t size, uint64_t alignment,
                                        int firstBank, int lastBank) {
      if (size == 0) {
        throw std::runtime_error("Cannot allocate zero bytes");
      }
      if (firstBank < 0 || lastBank >= NUM_BANKS || firstBank > lastBank) {
        throw std::runtime_error("allocateMemFPGAInBankRange: invalid bank range");
      }

      uint64_t alloc_alignment = std::max<uint64_t>(alignment, PAGE_SIZE);
      uint64_t aligned_size = alignUp(size, alloc_alignment);

      if (ramaMapping_.enabled()) {
        const int stripeFirst = static_cast<int>(ramaMapping_.first_bank);
        const int stripeLast = static_cast<int>(ramaMapping_.first_bank +
                                                ramaMapping_.memory_count - 1);
        if (firstBank <= stripeLast && lastBank >= stripeFirst)
          return allocateStripedBoSpan(aligned_size, alignment);
      }

      if (aligned_size <= BANK_SIZE) {
        return allocateSingleBoInBankRange(aligned_size, alignment, firstBank, lastBank);
      }

      return allocateContiguousBoSpanInBankRange(aligned_size, alignment,
                                                 firstBank, lastBank);
    }

    // Additive, non-breaking helper (used only by the triangleCountDecoupled
    // telemetry path): allocate a physically-contiguous span that BEGINS at a
    // specific HBM bank. Because each U55C HBM bank has a fixed base address
    // (bank * BANK_SIZE), this lets the caller pin a region to a known device
    // address (e.g. firstBank=16 -> 0x2_0000_0000). The BOs are registered in
    // addressBufferMap exactly like the normal allocators, so copyTo/FromDevice
    // work over the region. No existing method or allocation behavior is changed.
    uint64_t allocateMemFPGASpanFromBank(uint64_t size, uint64_t alignment, int firstBank) {
      if (size == 0) {
        throw std::runtime_error("Cannot allocate zero bytes");
      }
      if (firstBank < 0 || firstBank >= NUM_BANKS) {
        throw std::runtime_error("allocateMemFPGASpanFromBank: firstBank out of range");
      }

      uint64_t alloc_alignment = std::max<uint64_t>(alignment, PAGE_SIZE);
      uint64_t aligned_size = alignUp(size, alloc_alignment);

      uint64_t remaining = aligned_size;
      uint64_t expected_addr = 0;
      uint64_t base_addr = 0;
      std::vector<std::pair<uint64_t, BufferInfo>> span;

      for (int bank = firstBank; bank < NUM_BANKS && remaining > 0; ++bank) {
        uint64_t chunk_size = std::min<uint64_t>(remaining, BANK_SIZE);
        if (availableBytes[bank] < chunk_size) {
          throw std::runtime_error(
              "allocateMemFPGASpanFromBank: a bank in the requested span has insufficient free space");
        }

        // Normal (host-mapped) BO rather than device_only: the telemetry watcher
        // writes here directly (not as a kernel argument), and a host-mapped BO
        // lets bo.read()/sync() reflect those device-side writes. device_only BOs
        // are only safe when the kernel owns them as an argument.
        auto buffer = xrt::bo(dev_, chunk_size, xrt::bo::flags::normal, bank);
        uint64_t addr = buffer.address();

        if (span.empty()) {
          if (alignment != 0 && addr % alignment != 0) {
            throw std::runtime_error(
                "allocateMemFPGASpanFromBank: base address does not satisfy alignment");
          }
          base_addr = addr;
        } else if (addr != expected_addr) {
          throw std::runtime_error(
              "allocateMemFPGASpanFromBank: span banks are not address-contiguous");
        }

        expected_addr = addr + chunk_size;
        remaining -= chunk_size;
        span.emplace_back(addr, BufferInfo{std::move(buffer), chunk_size, bank});
      }

      if (remaining != 0) {
        throw std::runtime_error(
            "allocateMemFPGASpanFromBank: not enough banks at/above firstBank for the requested span");
      }

      for (auto &entry : span) {
        availableBytes[entry.second.bank_index] -= entry.second.size;
        addressBufferMap.emplace(entry.first, std::move(entry.second));
      }
      return base_addr;
    }


  void copyToDevice(uint64_t dest_addr, uint8_t const* src, uint64_t size) override
  {
    if (size != 0 && ramaMapping_.contains(dest_addr)) {
      if (size > ramaMapping_.windowEnd() - dest_addr)
        throw std::runtime_error("RAMA-striped host write crosses the compute-memory window");
      copyToDeviceStriped(dest_addr, src, size);
      return;
    }
    copyToDeviceLinear(dest_addr, src, size);
  }

  void copyToDeviceLinear(uint64_t dest_addr, uint8_t const* src, uint64_t size)
  {
    uint64_t current_addr = dest_addr;
    uint64_t left = size;

    while (left > 0)
    {
        auto located = findBuffer(current_addr);
        if (located == addressBufferMap.end()) {
            throw std::runtime_error("Missing buffer for device address");
        }

        auto &page_buffer = located->second.buffer;
        uint64_t page_offset = current_addr - located->first;
        uint64_t chunk = std::min<uint64_t>(left, located->second.size - page_offset);

        page_buffer.write(src, static_cast<size_t>(chunk), static_cast<size_t>(page_offset));
        if (!isEmulation()) {
          try {
            page_buffer.sync(XCL_BO_SYNC_BO_TO_DEVICE, static_cast<size_t>(chunk),
                             static_cast<size_t>(page_offset));
          } catch (const std::exception &e) {
            std::cerr << "[XRTMemory] WARNING: sync-to-device failed at 0x"
                      << std::hex << current_addr << "+" << page_offset
                      << " size=0x" << chunk << std::dec << ": " << e.what()
                      << "\n";
          }
        }

        left -= chunk;
        src += chunk;
        current_addr += chunk;
    }
  }


  void copyFromDevice(uint8_t* dest, uint64_t src_addr, uint64_t size) override
  {
    if (size != 0 && ramaMapping_.contains(src_addr)) {
      if (size > ramaMapping_.windowEnd() - src_addr)
        throw std::runtime_error("RAMA-striped host read crosses the compute-memory window");
      copyFromDeviceStriped(dest, src_addr, size);
      return;
    }
    copyFromDeviceLinear(dest, src_addr, size);
  }

  void copyFromDeviceLinear(uint8_t* dest, uint64_t src_addr, uint64_t size)
  {
    uint64_t current_addr = src_addr;
    uint64_t left = size;

    while (left > 0)
    {
        auto located = findBuffer(current_addr);
        if (located == addressBufferMap.end()) {
            throw std::runtime_error("Missing buffer for device address");
        }

        auto &page_buffer = located->second.buffer;
        uint64_t page_offset = current_addr - located->first;
        uint64_t chunk = std::min<uint64_t>(left, located->second.size - page_offset);

        if (!isEmulation()) {
          try {
            page_buffer.sync(XCL_BO_SYNC_BO_FROM_DEVICE, static_cast<size_t>(chunk),
                             static_cast<size_t>(page_offset));
          } catch (const std::exception &e) {
            std::cerr << "[XRTMemory] WARNING: sync-from-device failed at 0x"
                      << std::hex << current_addr << "+" << page_offset
                      << " size=0x" << chunk << std::dec << ": " << e.what()
                      << "\n";
          }
        }
        page_buffer.read(dest, static_cast<size_t>(chunk), static_cast<size_t>(page_offset));

        dest += chunk;
        left -= chunk;
        current_addr += chunk;
    }
  }

  // Pull a device region into its host backing so a subsequent copyFromDevice
  // reflects writes made directly by the kernel (e.g. the telemetry watcher
  // reaches its BO by hardcoded address, not as a kernel argument, so the host
  // side is stale until DMA'd back). Returns true if a sync was issued for the
  // whole range.
  bool syncRegionFromDevice(uint64_t addr, uint64_t size) {
    uint64_t current_addr = addr;
    uint64_t left = size;
    while (left > 0) {
      auto located = findBuffer(current_addr);
      if (located == addressBufferMap.end()) {
        return false;
      }
      auto &page_buffer = located->second.buffer;
      uint64_t page_offset = current_addr - located->first;
      uint64_t chunk = std::min<uint64_t>(left, located->second.size - page_offset);
      page_buffer.sync(XCL_BO_SYNC_BO_FROM_DEVICE, static_cast<size_t>(chunk), static_cast<size_t>(page_offset));
      left -= chunk;
      current_addr += chunk;
    }
    return true;
  }

  // Counterpart to syncRegionFromDevice: push host backing down to device memory.
  bool syncRegionToDevice(uint64_t addr, uint64_t size) {
    uint64_t current_addr = addr;
    uint64_t left = size;
    while (left > 0) {
      auto located = findBuffer(current_addr);
      if (located == addressBufferMap.end()) {
        return false;
      }
      auto &page_buffer = located->second.buffer;
      uint64_t page_offset = current_addr - located->first;
      uint64_t chunk = std::min<uint64_t>(left, located->second.size - page_offset);
      page_buffer.sync(XCL_BO_SYNC_BO_TO_DEVICE, static_cast<size_t>(chunk), static_cast<size_t>(page_offset));
      left -= chunk;
      current_addr += chunk;
    }
    return true;
  }

  ~XRTMemory() {}

private:
    struct StripePiece {
      uint64_t logical_offset;
      uint64_t size;
    };

    struct StripeGather {
      bool used = false;
      uint64_t physical_start = 0;
      uint64_t physical_next = 0;
      std::vector<uint8_t> bytes;
      std::vector<StripePiece> pieces;
    };

    std::vector<StripeGather> buildStripeGathers(uint64_t logical_addr,
                                                  uint64_t size) const {
      std::vector<StripeGather> gathers(
          static_cast<size_t>(ramaMapping_.memory_count));
      const uint64_t reserve =
          size / ramaMapping_.memory_count + ramaMapping_.fragment_bytes;
      for (auto &g : gathers)
        g.bytes.reserve(static_cast<size_t>(reserve));

      uint64_t cursor = logical_addr;
      uint64_t logical_offset = 0;
      uint64_t left = size;
      while (left > 0) {
        const uint64_t chunk = std::min<uint64_t>(
            left, ramaMapping_.bytesUntilFragmentEnd(cursor));
        const size_t stripe =
            static_cast<size_t>(ramaMapping_.stripeIndex(cursor));
        const uint64_t physical = ramaMapping_.physicalAddress(cursor);
        StripeGather &g = gathers[stripe];
        if (!g.used) {
          g.used = true;
          g.physical_start = physical;
          g.physical_next = physical;
        }
        if (physical != g.physical_next)
          throw std::runtime_error("RAMA mapping produced a non-contiguous per-bank transfer");
        g.pieces.push_back(StripePiece{logical_offset, chunk});
        g.physical_next += chunk;
        cursor += chunk;
        logical_offset += chunk;
        left -= chunk;
      }
      return gathers;
    }

    void copyToDeviceStriped(uint64_t dest_addr, uint8_t const *src,
                             uint64_t size) {
      std::vector<StripeGather> gathers = buildStripeGathers(dest_addr, size);
      for (StripeGather &g : gathers) {
        if (!g.used)
          continue;
        for (const StripePiece &piece : g.pieces) {
          const size_t old_size = g.bytes.size();
          g.bytes.resize(old_size + static_cast<size_t>(piece.size));
          std::memcpy(g.bytes.data() + old_size, src + piece.logical_offset,
                      static_cast<size_t>(piece.size));
        }
        copyToDeviceLinear(g.physical_start, g.bytes.data(), g.bytes.size());
      }
    }

    void copyFromDeviceStriped(uint8_t *dest, uint64_t src_addr,
                               uint64_t size) {
      std::vector<StripeGather> gathers = buildStripeGathers(src_addr, size);
      for (StripeGather &g : gathers) {
        if (!g.used)
          continue;
        uint64_t bytes = 0;
        for (const StripePiece &piece : g.pieces)
          bytes += piece.size;
        g.bytes.resize(static_cast<size_t>(bytes));
        copyFromDeviceLinear(g.bytes.data(), g.physical_start, bytes);
        size_t gathered_offset = 0;
        for (const StripePiece &piece : g.pieces) {
          std::memcpy(dest + piece.logical_offset,
                      g.bytes.data() + gathered_offset,
                      static_cast<size_t>(piece.size));
          gathered_offset += static_cast<size_t>(piece.size);
        }
      }
    }

    uint64_t allocateStripedBoSpan(uint64_t aligned_size,
                                   uint64_t requested_alignment) {
      const uint64_t stripe_span =
          ramaMapping_.fragment_bytes * ramaMapping_.memory_count;
      aligned_size = alignUp(aligned_size, stripe_span);
      const uint64_t per_bank_size = aligned_size / ramaMapping_.memory_count;
      if (per_bank_size == 0 || per_bank_size > BANK_SIZE)
        throw std::runtime_error("RAMA-striped allocation exceeds its HBM window");

      std::vector<std::pair<uint64_t, BufferInfo>> buffers;
      buffers.reserve(static_cast<size_t>(ramaMapping_.memory_count));
      uint64_t common_offset = UINT64_MAX;
      for (uint64_t stripe = 0; stripe < ramaMapping_.memory_count; ++stripe) {
        const int bank = static_cast<int>(ramaMapping_.first_bank + stripe);
        if (availableBytes[bank] < per_bank_size)
          throw std::runtime_error("RAMA-striped allocation exhausted an HBM bank");

        auto buffer = xrt::bo(dev_, per_bank_size, xrt::bo::flags::device_only,
                              bank);
        const uint64_t addr = buffer.address();
        const uint64_t bank_base = static_cast<uint64_t>(bank) * BANK_SIZE;
        if (addr < bank_base || addr + per_bank_size > bank_base + BANK_SIZE)
          throw std::runtime_error("XRT returned a BO outside its requested HBM bank");
        const uint64_t offset = addr - bank_base;
        if (common_offset == UINT64_MAX)
          common_offset = offset;
        else if (offset != common_offset)
          throw std::runtime_error(
              "RAMA-striped BOs did not receive matching offsets in every HBM bank");
        buffers.emplace_back(
            addr, BufferInfo{std::move(buffer), per_bank_size, bank});
      }

      const uint64_t logical_addr =
          ramaMapping_.windowBase() + common_offset * ramaMapping_.memory_count;
      if (logical_addr + aligned_size > ramaMapping_.windowEnd() ||
          (requested_alignment != 0 &&
           logical_addr % requested_alignment != 0))
        throw std::runtime_error("RAMA-striped logical allocation has an invalid address");

      for (auto &entry : buffers) {
        availableBytes[entry.second.bank_index] -= entry.second.size;
        addressBufferMap.emplace(entry.first, std::move(entry.second));
      }
      return logical_addr;
    }

    // Under XCL_EMULATION_MODE=hw_emu, register access is a protobuf
    // request/response over a socket to the xsim process. Deep into a long run
    // (thousands of polls) that channel can drop or corrupt a SINGLE response --
    // the symptom is a libprotobuf "missing required field: valid" parse error and
    // an xrt_core::system_error thrown out of read_register/write_register, which
    // (being uncaught) std::terminate()s the whole run. Every access routed through
    // here targets an idempotent management status/config register, so a bounded
    // retry with backoff rides over the transient hiccup instead of aborting. On
    // real hardware the first attempt succeeds, so this adds no overhead there.
    static constexpr int kRegRetryAttempts = 8;

    template <class Fn>
    auto retryRegAccess(const char *what, uint64_t addr, Fn &&fn) -> decltype(fn())
    {
      for (int attempt = 1;; ++attempt)
      {
        try
        {
          return fn();
        }
        catch (const std::exception &e)
        {
          if (attempt >= kRegRetryAttempts)
          {
            std::cerr << "[XRTMemory] " << what << " at 0x" << std::hex << addr
                      << std::dec << " failed after " << attempt
                      << " attempts: " << e.what() << "\n";
            throw; // unrecoverable: let the caller's safety net tear down cleanly
          }
          std::cerr << "[XRTMemory] transient " << what << " failure at 0x"
                    << std::hex << addr << std::dec << " (attempt " << attempt
                    << "/" << kRegRetryAttempts << "): " << e.what()
                    << " -- retrying\n";
          std::this_thread::sleep_for(std::chrono::milliseconds(50 * attempt));
        }
      }
    }

    static uint64_t alignUp(uint64_t value, uint64_t alignment) {
      if (alignment == 0) {
        return value;
      }
      uint64_t remainder = value % alignment;
      return remainder == 0 ? value : value + alignment - remainder;
    }

    auto findBuffer(uint64_t addr) -> decltype(addressBufferMap)::iterator {
      auto it = addressBufferMap.upper_bound(addr);
      if (it == addressBufferMap.begin()) {
        return addressBufferMap.end();
      }

      --it;
      if (addr >= it->first && addr < it->first + it->second.size) {
        return it;
      }
      return addressBufferMap.end();
    }

    uint64_t allocateSingleBo(uint64_t aligned_size, uint64_t requested_alignment) {
      return allocateSingleBoInBankRange(aligned_size, requested_alignment, 0, NUM_BANKS - 1);
    }

    uint64_t allocateSingleBoInBankRange(uint64_t aligned_size,
                                         uint64_t requested_alignment,
                                         int firstBank,
                                         int lastBank) {
      int bank = -1;
      uint64_t best_available = 0;
      for (int i = firstBank; i <= lastBank; ++i) {
        if (availableBytes[i] >= aligned_size && availableBytes[i] > best_available) {
          bank = i;
          best_available = availableBytes[i];
        }
      }

      if (bank < 0) {
        throw std::runtime_error("No HBM bank in the requested range has enough free space for a contiguous BO");
      }

      auto buffer = xrt::bo(dev_, aligned_size, xrt::bo::flags::device_only, bank);
      uint64_t addr = buffer.address();
      if (requested_alignment != 0 && addr % requested_alignment != 0) {
        throw std::runtime_error("XRT returned an address that does not satisfy requested alignment");
      }

      addressBufferMap.emplace(addr, BufferInfo{std::move(buffer), aligned_size, bank});
      availableBytes[bank] -= aligned_size;
      return addr;
    }

    uint64_t allocateContiguousBoSpan(uint64_t aligned_size, uint64_t requested_alignment) {
      return allocateContiguousBoSpanInBankRange(aligned_size, requested_alignment, 0, NUM_BANKS - 1);
    }

    uint64_t allocateContiguousBoSpanInBankRange(uint64_t aligned_size,
                                                 uint64_t requested_alignment,
                                                 int firstBank,
                                                 int lastBank) {
      for (int first_bank = firstBank; first_bank <= lastBank; ++first_bank) {
        uint64_t remaining = aligned_size;
        uint64_t expected_addr = 0;
        uint64_t base_addr = 0;
        std::vector<std::pair<uint64_t, BufferInfo>> span;

        for (int bank = first_bank; bank <= lastBank && remaining > 0; ++bank) {
          uint64_t chunk_size = std::min<uint64_t>(remaining, BANK_SIZE);
          if (availableBytes[bank] < chunk_size) {
            span.clear();
            break;
          }

          try {
            auto buffer = xrt::bo(dev_, chunk_size, xrt::bo::flags::device_only, bank);
            uint64_t addr = buffer.address();

            if (span.empty()) {
              if (requested_alignment != 0 && addr % requested_alignment != 0) {
                span.clear();
                break;
              }
              base_addr = addr;
            } else if (addr != expected_addr) {
              span.clear();
              break;
            }

            expected_addr = addr + chunk_size;
            remaining -= chunk_size;
            span.emplace_back(addr, BufferInfo{std::move(buffer), chunk_size, bank});
          } catch (const std::exception&) {
            span.clear();
            break;
          }
        }

        if (remaining == 0 && !span.empty()) {
          for (auto &entry : span) {
            availableBytes[entry.second.bank_index] -= entry.second.size;
            addressBufferMap.emplace(entry.first, std::move(entry.second));
          }
          return base_addr;
        }
      }

      throw std::runtime_error(
          "Could not allocate a physically contiguous multi-bank HBM span in the requested range. "
          "The xclbin/platform must expose adjacent HBM bank address windows, "
          "and the banks in the span must be free when the large allocation is made.");
    }

};

#else // MTI_SYSTEMC

// QuestaSim/SystemC build. XRT is not available, but CountDecoupledDriver's
// XRT-only code paths are all guarded by `dynamic_cast<XRTMemory*>(memory_)`,
// which returns null under simulation (questaMemory is the real backend). This
// stub only has to make those branches COMPILE; none of its methods ever run.
// Keep the method signatures in sync with the real XRTMemory above.
struct XRTMemory : Memory
{
  // Memory interface (never used in sim; questaMemory services the DPI bridge).
  void writeReg32(uint64_t, uint32_t) override {}
  void writeReg64(uint64_t, uint64_t) override {}
  uint32_t readReg32(uint64_t) override { return 0; }
  uint64_t readReg64(uint64_t) override { return 0; }
  void copyToDevice(uint64_t, uint8_t const *, uint64_t) override {}
  void copyFromDevice(uint8_t *, uint64_t, uint64_t) override {}
  uint64_t allocateMemFPGA(uint64_t, uint64_t) override { return 0; }

  // XRT-specific helpers the shared driver references inside dead branches.
  bool isEmulation() const { return true; }
  void setDefaultBankRange(int, int) {}
  void clearHBMBankRange(int, int) {}
  uint64_t allocateMemFPGAInBankRange(uint64_t, uint64_t, int, int) { return 0; }
  uint64_t allocateMemFPGASpanFromBank(uint64_t, uint64_t, int) { return 0; }
  bool syncRegionFromDevice(uint64_t, uint64_t) { return false; }
  bool syncRegionToDevice(uint64_t, uint64_t) { return false; }
};

#endif // MTI_SYSTEMC
