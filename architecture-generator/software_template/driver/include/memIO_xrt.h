#pragma once
#include <memIO.h>

#include <algorithm>
#include <bits/stdc++.h>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <stdlib.h>
#include <vector>

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


    public:
    XRTMemory(xrt::device &dev, xrt::ip &hardCilk_ip) : availableBytes(NUM_BANKS){
      std::fill(availableBytes.begin(), availableBytes.end(), BANK_SIZE);
      dev_ = dev;
      hardCilk_ip_ = hardCilk_ip;
    }

    void writeReg32(uint64_t addr, uint32_t value){
      hardCilk_ip_.write_register(addr, value);
    }

    void writeReg64(uint64_t addr, uint64_t value){
      hardCilk_ip_.write_register(addr, static_cast<uint32_t>(value & 0xFFFFFFFF));
      hardCilk_ip_.write_register(addr + 4, static_cast<uint32_t>((value >> 32) & 0xFFFFFFFF));
    }

    uint32_t readReg32(uint64_t addr){
      uint32_t value = hardCilk_ip_.read_register(addr);
      return value;
    }

    uint64_t readReg64(uint64_t addr){
      uint32_t low, high;
      low = hardCilk_ip_.read_register(addr);
      high = hardCilk_ip_.read_register(addr + 4);
      u_int64_t value = static_cast<uint64_t>(low) | (static_cast<uint64_t>(high) << 32);
      return value;
    }

    uint64_t allocateMemFPGA(uint64_t size, uint64_t alignment){
      if (size == 0) {
        throw std::runtime_error("Cannot allocate zero bytes");
      }

      uint64_t alloc_alignment = std::max<uint64_t>(alignment, PAGE_SIZE);
      uint64_t aligned_size = alignUp(size, alloc_alignment);

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
      defaultFirstBank_ = firstBank;
      defaultLastBank_ = lastBank;
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

        left -= chunk;
        src += chunk;
        current_addr += chunk;
    }
  }


  void copyFromDevice(uint8_t* dest, uint64_t src_addr, uint64_t size) override
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
