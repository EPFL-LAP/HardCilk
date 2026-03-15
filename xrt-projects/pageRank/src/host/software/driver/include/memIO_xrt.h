// #pragma once
// #include <stdlib.h>
// #include <memory>
// #include <stdint.h>
// #include <memIO.h>
// #include <vector>
// #include <bits/stdc++.h>

// #include <xrt/xrt_bo.h>
// #include <xrt/xrt_device.h>
// #include <experimental/xrt_xclbin.h>
// #include <experimental/xrt_ip.h>
// #define  PAGE_SIZE 4096 *1024

// struct XRTMemory : Memory{

//     private:
//     // There is 32 memory banks each with size 512 MB, track memory in each of them for XRT    
//     std::vector <int> availablePages;
//     std::map<uint64_t, xrt::bo> addressBufferMap;
//     xrt::device dev_;
//     xrt::ip hardCilk_ip_;


//     public:
//     XRTMemory(xrt::device &dev, xrt::ip &hardCilk_ip) : availablePages(32){
//       std::fill(availablePages.begin(), availablePages.end(), (512*1024*1024) / PAGE_SIZE);
//       dev_ = dev;
//       hardCilk_ip_ = hardCilk_ip;
//     }

//     void writeReg32(uint64_t addr, uint32_t value){
//       hardCilk_ip_.write_register(addr, value);
//     }

//     void writeReg64(uint64_t addr, uint64_t value){
//       hardCilk_ip_.write_register(addr, static_cast<uint32_t>(value & 0xFFFFFFFF));
//       hardCilk_ip_.write_register(addr + 4, static_cast<uint32_t>((value >> 32) & 0xFFFFFFFF));
//     }

//     uint32_t readReg32(uint64_t addr){
//       uint32_t value = hardCilk_ip_.read_register(addr);
//       return value;
//     }

//     uint64_t readReg64(uint64_t addr){
//       uint32_t low, high;
//       low = hardCilk_ip_.read_register(addr);
//       high = hardCilk_ip_.read_register(addr + 4);
//       u_int64_t value = static_cast<uint64_t>(low) | (static_cast<uint64_t>(high) << 32);
//       return value;
//     }

//     uint64_t allocateMemFPGA(uint64_t size, uint64_t alignment){
//       int pages_4k_count = std::ceil(size/(PAGE_SIZE*1.0));
//       int bank_index = 0;

//       uint64_t initial_address = -1;
      
//       while(pages_4k_count && bank_index < 32){
//         if(availablePages[bank_index] > 0){
//           pages_4k_count--;
//           availablePages[bank_index]--;
//           // Create a buffer
//           auto buffer = xrt::bo(dev_, PAGE_SIZE, bank_index);
//           uint64_t addr = buffer.address();
          
//           if(initial_address == -1){
//             initial_address = addr;
//           }

//           addressBufferMap[addr] = std::move(buffer);
//         } else {
//           bank_index++;
//         }
//       }
      
//       if(bank_index == 32){
//         throw std::runtime_error("No available 4K pages in any bank");
//       }

//       return initial_address;
//     } 


//   void copyToDevice(uint64_t dest_addr, uint8_t const* src, uint32_t size)
//   {
//     uint64_t page_addr   = dest_addr & ~(PAGE_SIZE - 1);
//     uint64_t page_offset = dest_addr &  (PAGE_SIZE - 1);
//     uint32_t left        = size;

//     while (left > 0)
//     {
//         auto it = addressBufferMap.find(page_addr);
//         if (it == addressBufferMap.end()) {
//             throw std::runtime_error("Missing buffer for address");
//         }

//         auto &page_buffer = it->second;

//         uint8_t* page_ptr = page_buffer.map<uint8_t*>();
//         if (!page_ptr) {
//             throw std::runtime_error("Failed to map page buffer");
//         }

//         uint32_t chunk = std::min<uint64_t>(left, PAGE_SIZE - page_offset);

//         std::copy(src, src + chunk, page_ptr + page_offset);

//         page_buffer.sync(XCL_BO_SYNC_BO_TO_DEVICE, chunk, page_offset);

//         left        -= chunk;
//         src         += chunk;
//         page_addr   += PAGE_SIZE;
//         page_offset  = 0;
//     }
//   }


//   void copyFromDevice(uint8_t* dest, uint64_t src_addr, uint32_t size)
//   {
//     uint64_t page_addr   = src_addr & ~(PAGE_SIZE - 1);
//     uint64_t page_offset = src_addr &  (PAGE_SIZE - 1);
//     uint32_t left        = size;

//     while (left > 0)
//     {
//         // Look up page buffer (do NOT use operator[] → inserts empty entry)
//         auto it = addressBufferMap.find(page_addr);
//         if (it == addressBufferMap.end()) {
//             throw std::runtime_error("Missing buffer for device address");
//         }

//         auto& page_buffer = it->second;

//         // Sync BO from device into host before reading
//         page_buffer.sync(XCL_BO_SYNC_BO_FROM_DEVICE);

//         // Map the page into host memory
//         uint8_t* page_ptr = page_buffer.map<uint8_t*>();
//         if (!page_ptr) {
//             throw std::runtime_error("Failed to map BO for reading");
//         }

//         // Compute how many bytes to copy from this page
//         uint32_t chunk = std::min<uint64_t>(left, PAGE_SIZE - page_offset);

//         // Copy data out of the page and into dest
//         std::copy(page_ptr + page_offset, page_ptr + page_offset + chunk, dest);

//         // Advance pointers
//         dest       += chunk;
//         left       -= chunk;
//         page_addr  += PAGE_SIZE;
//         page_offset = 0;
//     }
//   }

//   ~XRTMemory() {}


// };


#pragma once
#include <stdlib.h>
#include <memory>
#include <stdint.h>
#include <memIO.h>
#include <vector>
#include <map>
#include <algorithm>
#include <stdexcept>
#include <cmath>
#include <iostream>

#include <xrt/xrt_bo.h>
#include <xrt/xrt_device.h>
#include <experimental/xrt_xclbin.h>
#include <experimental/xrt_ip.h>

#define PAGE_SIZE (4096 * 16)  // 64KB pages
#define BANK_SIZE (512ULL * 1024 * 1024)  // 512MB per bank
#define NUM_BANKS 32

struct XRTMemory : Memory {
private:
    struct BufferInfo {
        xrt::bo buffer;
        uint64_t size;
        int bank_index;
    };

    // Map base address -> buffer info
    std::map<uint64_t, BufferInfo> buffers;
    
    // Track available memory per bank
    std::vector<uint64_t> availableMemory;
    
    xrt::device dev_;
    xrt::ip hardCilk_ip_;
    
    // Statistics
    size_t total_bos_created = 0;
    size_t total_allocations = 0;

public:
    XRTMemory(xrt::device &dev, xrt::ip &hardCilk_ip) 
        : availableMemory(NUM_BANKS), dev_(dev), hardCilk_ip_(hardCilk_ip) {
        std::fill(availableMemory.begin(), availableMemory.end(), BANK_SIZE);
    }

    void writeReg32(uint64_t addr, uint32_t value) {
        hardCilk_ip_.write_register(addr, value);
    }

    void writeReg64(uint64_t addr, uint64_t value) {
        hardCilk_ip_.write_register(addr, static_cast<uint32_t>(value & 0xFFFFFFFF));
        hardCilk_ip_.write_register(addr + 4, static_cast<uint32_t>((value >> 32) & 0xFFFFFFFF));
    }

    uint32_t readReg32(uint64_t addr) {
        return hardCilk_ip_.read_register(addr);
    }

    uint64_t readReg64(uint64_t addr) {
        uint32_t low = hardCilk_ip_.read_register(addr);
        uint32_t high = hardCilk_ip_.read_register(addr + 4);
        return static_cast<uint64_t>(low) | (static_cast<uint64_t>(high) << 32);
    }

    // Find bank with most available space that can fit the size
    int selectBank(uint64_t size) {
        int best_bank = -1;
        uint64_t max_available = 0;
        
        for (int i = 0; i < NUM_BANKS; i++) {
            if (availableMemory[i] >= size && availableMemory[i] > max_available) {
                best_bank = i;
                max_available = availableMemory[i];
            }
        }
        
        if (best_bank == -1) {
            std::cerr << "ERROR: No single bank has " << (size / (1024.0 * 1024.0)) 
                     << " MB available" << std::endl;
            printMemoryUsage();
        }
        
        return best_bank;
    }

    // CRITICAL: Allocate CONTIGUOUS memory - single BO per allocation
    uint64_t allocateMemFPGA(uint64_t size, uint64_t alignment) {
        if (size == 0) {
            throw std::runtime_error("Cannot allocate zero bytes");
        }

        total_allocations++;
        
        // Align to page size
        uint64_t aligned_size = ((size + PAGE_SIZE - 1) / PAGE_SIZE) * PAGE_SIZE;
        
        // Check if any single bank can fit this allocation
        if (aligned_size > BANK_SIZE) {
            std::cerr << "ERROR: Single allocation (" << (aligned_size / (1024.0 * 1024.0)) 
                     << " MB) exceeds single bank capacity (" << (BANK_SIZE / (1024.0 * 1024.0)) 
                     << " MB)" << std::endl;
            throw std::runtime_error("Allocation too large for single bank (max 512MB per allocation)");
        }
        
        // Find a bank with enough contiguous space
        int bank = selectBank(aligned_size);
        
        if (bank == -1) {
            // Check total available across all banks
            uint64_t total_avail = 0;
            for (auto m : availableMemory) total_avail += m;
            
            std::cerr << "ERROR: No single bank has enough space for " 
                     << (aligned_size / (1024.0 * 1024.0)) << " MB allocation" << std::endl;
            std::cerr << "Total available across all banks: " 
                     << (total_avail / (1024.0 * 1024.0)) << " MB" << std::endl;
            printMemoryUsage();
            
            throw std::runtime_error("Cannot find contiguous memory in any single bank");
        }

        try {
            // Create a SINGLE buffer object for the entire allocation
            // This guarantees physical contiguity within the bank
            auto buffer = xrt::bo(dev_, aligned_size, bank);
            uint64_t addr = buffer.address();
            
            // Store buffer
            BufferInfo info{std::move(buffer), aligned_size, bank};
            buffers[addr] = std::move(info);
            
            // Update tracking
            availableMemory[bank] -= aligned_size;
            total_bos_created++;
            
            std::cout << "Allocation #" << total_allocations 
                     << ": Created contiguous BO of " << (aligned_size / (1024.0 * 1024.0)) 
                     << " MB in bank " << bank 
                     << " (addr: 0x" << std::hex << addr << std::dec << ")"
                     << std::endl;
            
            return addr;
            
        } catch (const std::exception& e) {
            std::cerr << "BO allocation failed: " << e.what() << std::endl;
            std::cerr << "  Requested size: " << (aligned_size / (1024.0 * 1024.0)) << " MB" << std::endl;
            std::cerr << "  Bank: " << bank << std::endl;
            std::cerr << "  Total BOs created so far: " << total_bos_created << std::endl;
            std::cerr << "  Available in bank " << bank << ": " 
                     << (availableMemory[bank] / (1024.0 * 1024.0)) << " MB" << std::endl;
            printMemoryUsage();
            throw;
        }
    }

    // Find which buffer contains an address
    BufferInfo* findBuffer(uint64_t addr) {
        auto it = buffers.upper_bound(addr);
        if (it != buffers.begin()) {
            --it;
            uint64_t buf_start = it->first;
            uint64_t buf_end = buf_start + it->second.size;
            
            if (addr >= buf_start && addr < buf_end) {
                return &(it->second);
            }
        }
        return nullptr;
    }

    void copyToDevice(uint64_t dest_addr, uint8_t const* src, uint32_t size) {
        // Since each allocation is contiguous, this should typically be a single BO
        uint64_t current_addr = dest_addr;
        uint32_t remaining = size;

        while (remaining > 0) {
            BufferInfo* buf_info = findBuffer(current_addr);
            if (!buf_info) {
                throw std::runtime_error("Address 0x" + std::to_string(current_addr) + 
                                       " not in any allocated buffer");
            }

            // Find buffer start address
            uint64_t buf_start = 0;
            for (auto& [addr, info] : buffers) {
                if (&info == buf_info) {
                    buf_start = addr;
                    break;
                }
            }

            uint64_t offset = current_addr - buf_start;
            uint32_t chunk = std::min<uint64_t>(remaining, buf_info->size - offset);

            uint8_t* ptr = buf_info->buffer.map<uint8_t*>();
            if (!ptr) {
                throw std::runtime_error("Failed to map buffer for writing");
            }

            std::copy(src, src + chunk, ptr + offset);
            buf_info->buffer.sync(XCL_BO_SYNC_BO_TO_DEVICE, chunk, offset);

            remaining -= chunk;
            src += chunk;
            current_addr += chunk;
        }
    }

    void copyFromDevice(uint8_t* dest, uint64_t src_addr, uint32_t size) {
        uint64_t current_addr = src_addr;
        uint32_t remaining = size;

        while (remaining > 0) {
            BufferInfo* buf_info = findBuffer(current_addr);
            if (!buf_info) {
                throw std::runtime_error("Address 0x" + std::to_string(src_addr) + 
                                       " not in any allocated buffer");
            }

            // Find buffer start address
            uint64_t buf_start = 0;
            for (auto& [addr, info] : buffers) {
                if (&info == buf_info) {
                    buf_start = addr;
                    break;
                }
            }

            uint64_t offset = current_addr - buf_start;
            uint32_t chunk = std::min<uint64_t>(remaining, buf_info->size - offset);

            buf_info->buffer.sync(XCL_BO_SYNC_BO_FROM_DEVICE);
            
            uint8_t* ptr = buf_info->buffer.map<uint8_t*>();
            if (!ptr) {
                throw std::runtime_error("Failed to map buffer for reading");
            }

            std::copy(ptr + offset, ptr + offset + chunk, dest);

            remaining -= chunk;
            dest += chunk;
            current_addr += chunk;
        }
    }

    void printMemoryUsage() const {
        std::cout << "\n=== HBM Memory Status ===" << std::endl;
        std::cout << "Total BOs created: " << total_bos_created << std::endl;
        std::cout << "Total allocations: " << total_allocations << std::endl;
        std::cout << "\nPer-Bank Status:" << std::endl;
        
        uint64_t total_used = 0;
        uint64_t total_available = 0;
        
        for (int i = 0; i < NUM_BANKS; i++) {
            uint64_t used = BANK_SIZE - availableMemory[i];
            std::cout << "  Bank " << std::setw(2) << i << ": ";
            if (used > 0) {
                std::cout << std::setw(6) << std::fixed << std::setprecision(1)
                         << (used / (1024.0 * 1024.0)) << " MB used, "
                         << std::setw(6) << (availableMemory[i] / (1024.0 * 1024.0)) << " MB free";
            } else {
                std::cout << "  [empty]  " << std::setw(6) 
                         << (availableMemory[i] / (1024.0 * 1024.0)) << " MB free";
            }
            std::cout << std::endl;
            
            total_used += used;
            total_available += availableMemory[i];
        }
        
        std::cout << "\nTotal: " << (total_used / (1024.0 * 1024.0)) << " MB used, "
                 << (total_available / (1024.0 * 1024.0)) << " MB available" << std::endl;
        std::cout << "========================\n" << std::endl;
    }

    ~XRTMemory() {
        std::cout << "Destroying XRTMemory. Total BOs created: " << total_bos_created << std::endl;
        buffers.clear();
    }
};