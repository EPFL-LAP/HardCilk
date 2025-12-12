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
#define  PAGE_SIZE 4096 *4

struct XRTMemory : Memory{

    private:
    // There is 32 memory banks each with size 512 MB, track memory in each of them for XRT    
    std::vector <int> availablePages;
    std::map<uint64_t, xrt::bo> addressBufferMap;
    xrt::device dev_;
    xrt::ip hardCilk_ip_;


    public:
    XRTMemory(xrt::device &dev, xrt::ip &hardCilk_ip) : availablePages(32){
      std::fill(availablePages.begin(), availablePages.end(), (512*1024*1024) / PAGE_SIZE);
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
      int pages_4k_count = std::ceil(size/(PAGE_SIZE*1.0));
      int bank_index = 0;

      uint64_t initial_address = -1;
      
      while(pages_4k_count && bank_index < 32){
        if(availablePages[bank_index] > 0){
          pages_4k_count--;
          availablePages[bank_index]--;
          // Create a buffer
          auto buffer = xrt::bo(dev_, PAGE_SIZE, bank_index);
          uint64_t addr = buffer.address();
          
          if(initial_address == -1){
            initial_address = addr;
          }

          addressBufferMap[addr] = std::move(buffer);
        } else {
          bank_index++;
        }
      }
      
      if(bank_index == 32){
        throw std::runtime_error("No available 4K pages in any bank");
      }

      return initial_address;
    } 


  void copyToDevice(uint64_t dest_addr, uint8_t const* src, uint32_t size)
  {
    uint64_t page_addr   = dest_addr & ~(PAGE_SIZE - 1);
    uint64_t page_offset = dest_addr &  (PAGE_SIZE - 1);
    uint32_t left        = size;

    while (left > 0)
    {
        auto it = addressBufferMap.find(page_addr);
        if (it == addressBufferMap.end()) {
            throw std::runtime_error("Missing buffer for address");
        }

        auto &page_buffer = it->second;

        uint8_t* page_ptr = page_buffer.map<uint8_t*>();
        if (!page_ptr) {
            throw std::runtime_error("Failed to map page buffer");
        }

        uint32_t chunk = std::min<uint64_t>(left, PAGE_SIZE - page_offset);

        std::copy(src, src + chunk, page_ptr + page_offset);

        page_buffer.sync(XCL_BO_SYNC_BO_TO_DEVICE, chunk, page_offset);

        left        -= chunk;
        src         += chunk;
        page_addr   += PAGE_SIZE;
        page_offset  = 0;
    }
  }


  void copyFromDevice(uint8_t* dest, uint64_t src_addr, uint32_t size)
  {
    uint64_t page_addr   = src_addr & ~(PAGE_SIZE - 1);
    uint64_t page_offset = src_addr &  (PAGE_SIZE - 1);
    uint32_t left        = size;

    while (left > 0)
    {
        // Look up page buffer (do NOT use operator[] → inserts empty entry)
        auto it = addressBufferMap.find(page_addr);
        if (it == addressBufferMap.end()) {
            throw std::runtime_error("Missing buffer for device address");
        }

        auto& page_buffer = it->second;

        // Sync BO from device into host before reading
        page_buffer.sync(XCL_BO_SYNC_BO_FROM_DEVICE);

        // Map the page into host memory
        uint8_t* page_ptr = page_buffer.map<uint8_t*>();
        if (!page_ptr) {
            throw std::runtime_error("Failed to map BO for reading");
        }

        // Compute how many bytes to copy from this page
        uint32_t chunk = std::min<uint64_t>(left, PAGE_SIZE - page_offset);

        // Copy data out of the page and into dest
        std::copy(page_ptr + page_offset, page_ptr + page_offset + chunk, dest);

        // Advance pointers
        dest       += chunk;
        left       -= chunk;
        page_addr  += PAGE_SIZE;
        page_offset = 0;
    }
  }

  ~XRTMemory() {}


};
