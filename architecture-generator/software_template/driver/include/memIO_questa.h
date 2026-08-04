#pragma once

#include <memIO.h>
#include <rama_striping.h>
#include <sc_dpiheader_questa.h>
#include <algorithm>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>
#include <utility>
#include <systemc.h>
#include<cmath>


#ifndef HARDCILK_FREEDMEMBLOCK_DEFINED
#define HARDCILK_FREEDMEMBLOCK_DEFINED
struct freedMemBlock
{
    uint64_t addr;
    uint64_t size;
};
#endif


struct questaMemory : Memory
{
    questaMemory()
    {
        ramaMapping_ = loadRamaHostMapping(std::string(), "questa");
        logRamaHostMapping(ramaMapping_, "questa");
    }

    private:
    uint64_t free_mem_base_addr = 0x0;
    std::vector<freedMemBlock> freed_mem_blocks;
    std::vector<std::pair<uint64_t, uint64_t>> trackMalloc;
    RamaHostMapping ramaMapping_;

    struct StripePiece
    {
        uint64_t logical_offset;
        uint64_t size;
    };

    struct StripeGather
    {
        bool used = false;
        uint64_t physical_start = 0;
        uint64_t physical_next = 0;
        std::vector<uint8_t> bytes;
        std::vector<StripePiece> pieces;
    };

    std::vector<StripeGather> buildStripeGathers(uint64_t logical_addr,
                                                  uint64_t size) const
    {
        std::vector<StripeGather> gathers(
            static_cast<size_t>(ramaMapping_.memory_count));
        const uint64_t reserve =
            size / ramaMapping_.memory_count + ramaMapping_.fragment_bytes;
        for (auto &g : gathers)
            g.bytes.reserve(static_cast<size_t>(reserve));

        uint64_t cursor = logical_addr;
        uint64_t logical_offset = 0;
        uint64_t left = size;
        while (left > 0)
        {
            const uint64_t chunk = std::min<uint64_t>(
                left, ramaMapping_.bytesUntilFragmentEnd(cursor));
            const size_t stripe =
                static_cast<size_t>(ramaMapping_.stripeIndex(cursor));
            const uint64_t physical = ramaMapping_.physicalAddress(cursor);
            StripeGather &g = gathers[stripe];
            if (!g.used)
            {
                g.used = true;
                g.physical_start = physical;
                g.physical_next = physical;
            }
            if (physical != g.physical_next)
                throw std::runtime_error(
                    "RAMA mapping produced a non-contiguous per-bank transfer");
            g.pieces.push_back(StripePiece{logical_offset, chunk});
            g.physical_next += chunk;
            cursor += chunk;
            logical_offset += chunk;
            left -= chunk;
        }
        return gathers;
    }

    public:
    uint32_t readReg32(uint64_t addr)
    {

#ifdef MTI_SYSTEMC
        svSetScope(svGetScopeFromName("TestBench.myModule"));
#endif

        uint8_t data[512];
        S_AXI_READ_REG(
            addr,
            reinterpret_cast<svBitVecVal *>(data),
            4);
        return *(reinterpret_cast<uint32_t *>(data));
    }

    uint64_t readReg64(uint64_t addr)
    {

#ifdef MTI_SYSTEMC
        svSetScope(svGetScopeFromName("TestBench.myModule"));
#endif

        uint8_t data[512];
        S_AXI_READ_REG(
            addr,
            reinterpret_cast<svBitVecVal *>(data),
            8);
        return *(reinterpret_cast<uint64_t *>(data));
    }

    void writeReg32(uint64_t addr, uint32_t value)
    {
#ifdef MTI_SYSTEMC
        svSetScope(svGetScopeFromName("TestBench.myModule"));
#endif

        uint8_t data[512];
        *(reinterpret_cast<uint32_t *>(data)) = value;
        S_AXI_WRITE_REG(
            addr,
            reinterpret_cast<svBitVecVal *>(data),
            4);
    }

    void writeReg64(uint64_t addr, uint64_t value)
    {
#ifdef MTI_SYSTEMC
        svSetScope(svGetScopeFromName("TestBench.myModule"));
#endif

        uint8_t data[512];
        *(reinterpret_cast<uint64_t *>(data)) = value;
        S_AXI_WRITE_REG(
            addr,
            reinterpret_cast<svBitVecVal *>(data),
            8);
    }

    void copyToDevice(uint64_t dest_addr, uint8_t const *src, uint64_t size)
    {
        if (size != 0 && ramaMapping_.contains(dest_addr))
        {
            if (size > ramaMapping_.windowEnd() - dest_addr)
                throw std::runtime_error("RAMA-striped Questa write crosses the compute-memory window");
            std::vector<StripeGather> gathers =
                buildStripeGathers(dest_addr, size);
            for (StripeGather &g : gathers)
            {
                if (!g.used)
                    continue;
                for (const StripePiece &piece : g.pieces)
                {
                    const size_t old_size = g.bytes.size();
                    g.bytes.resize(old_size + static_cast<size_t>(piece.size));
                    std::memcpy(g.bytes.data() + old_size,
                                src + piece.logical_offset,
                                static_cast<size_t>(piece.size));
                }
                copyToDeviceLinear(g.physical_start, g.bytes.data(),
                                   g.bytes.size());
            }
            return;
        }
        copyToDeviceLinear(dest_addr, src, size);
    }

    void copyToDeviceLinear(uint64_t dest_addr, uint8_t const *src, uint64_t size)
    {
#ifdef MTI_SYSTEMC
        svSetScope(svGetScopeFromName("TestBench.myModule"));
#endif

        // Create a local buffer to hold the data
        uint8_t *data_tmp = new uint8_t[size + 4096];

        // Copy the data to the local buffer
        memcpy(data_tmp, src, size);

        // Check if the size crosses 4KB boundary, if so, divide the data into 4KB chunks or less and send them
        uint64_t page_of_start_addr = dest_addr & 0xFFFFFFFFFFFFF000;
        uint64_t page_of_end_addr = (dest_addr + size) & 0xFFFFFFFFFFFFF000;

        std::vector<std::tuple<uint64_t, uint32_t, uint8_t *>> transactions;

        if (page_of_start_addr != page_of_end_addr || size > 4096)
        {
            // Get a transaction up to the 4KB boundary
            uint32_t size_to_boundary = 4096 - (dest_addr & 0xFFF);
            transactions.push_back(std::make_tuple(dest_addr, size_to_boundary, data_tmp));

            // Divide the rest of the transactions into 4KB chunks or less
            uint64_t current_addr = dest_addr + size_to_boundary;
            while (current_addr < dest_addr + size)
            {
                uint32_t current_size = 4096;
                if (dest_addr + size - current_addr < 4096)
                {
                    current_size = dest_addr + size - current_addr;
                }
                transactions.push_back(std::make_tuple(current_addr, current_size, data_tmp + current_addr - dest_addr));
                current_addr += 4096;
            }
        }
        else
        {
            transactions.push_back(std::make_tuple(dest_addr, size, data_tmp));
        }

        for (auto transaction : transactions)
        {
            uint32_t transaction_size = std::get<1>(transaction);
            uint64_t offset = 0;
            
            if(transaction_size == 0){
                continue;
            }
            for (uint32_t i = 6; i >= 0; i--)
            {
                // burst length is byte size of the transaction divide by 2^burst_size
                
                const uint32_t burst_length = (transaction_size / (1<<i)) - 1;
                uint32_t remaining_bytes = transaction_size % (1<<i);

                if (burst_length == -1 && remaining_bytes > 0)
                {
                    continue;
                }
                

                if (!(burst_length < 256) ||
                    !(std::get<2>(transaction) + offset < data_tmp + size) ||
                    !(std::get<2>(transaction) + offset + burst_length * (1 << i) <= data_tmp + size) ||
                    !(std::get<0>(transaction) + offset + burst_length * (1 << i) <= dest_addr + size))
                {

                    std::cout << "Error: " << std::endl;
                    std::cout << "burst_length: " << burst_length << std::endl;
                    std::cout << "transaction_size: " << transaction_size << std::endl;
                    std::cout << "i: " << i << std::endl;
                    std::cout << "offset: " << offset << std::endl;
                    std::cout << "remaining_bytes: " << remaining_bytes << std::endl;
                }

                // Print the details of the transaction
                uint64_t fpga_address = std::get<0>(transaction) + offset;
                uint64_t cpu_address = (uint64_t)std::get<2>(transaction) + offset;

                //std::cout << "Transaction Address to FPGA: " << std::hex << fpga_address << ", Size: " << transaction_size << ", Data address from the CPU: " << std::hex << cpu_address << std::endl << std::dec;
                
                S_AXI_WRITE_MEM(
                    fpga_address,
                    reinterpret_cast<svBitVecVal *>(cpu_address),
                    burst_length,
                    i);

                if (remaining_bytes == 0)
                {
                    break;
                }
                else
                {
                    offset += (burst_length + 1) * (1 << i);
                    transaction_size = remaining_bytes;
                    //std::cout << "Remaining bytes: " << remaining_bytes << ", new offset: " << offset << std::endl;
                }
            }
        }
        delete[] data_tmp;
    }

    void copyFromDevice(uint8_t *dest, uint64_t src_addr, uint64_t size)
    {
        if (size != 0 && ramaMapping_.contains(src_addr))
        {
            if (size > ramaMapping_.windowEnd() - src_addr)
                throw std::runtime_error("RAMA-striped Questa read crosses the compute-memory window");
            std::vector<StripeGather> gathers =
                buildStripeGathers(src_addr, size);
            for (StripeGather &g : gathers)
            {
                if (!g.used)
                    continue;
                uint64_t bytes = 0;
                for (const StripePiece &piece : g.pieces)
                    bytes += piece.size;
                g.bytes.resize(static_cast<size_t>(bytes));
                copyFromDeviceLinear(g.bytes.data(), g.physical_start, bytes);
                size_t gathered_offset = 0;
                for (const StripePiece &piece : g.pieces)
                {
                    std::memcpy(dest + piece.logical_offset,
                                g.bytes.data() + gathered_offset,
                                static_cast<size_t>(piece.size));
                    gathered_offset += static_cast<size_t>(piece.size);
                }
            }
            return;
        }
        copyFromDeviceLinear(dest, src_addr, size);
    }

    void copyFromDeviceLinear(uint8_t *dest, uint64_t src_addr, uint64_t size)
    {
#ifdef MTI_SYSTEMC
        svSetScope(svGetScopeFromName("TestBench.myModule"));
#endif

        // Create a local buffer to hold the data
        uint8_t *data = new uint8_t[size + 4096];

        // Check if the size crosses 4KB boundary, if so, divide the data into 4KB chunks or less and receieve the data
        uint64_t page_of_start_addr = src_addr & 0xFFFFFFFFFFFFF000;
        uint64_t page_of_end_addr = (src_addr + size) & 0xFFFFFFFFFFFFF000;

        std::vector<std::tuple<uint64_t, uint32_t, uint8_t *>> transactions;

        if (page_of_start_addr != page_of_end_addr || size > 4096)
        {

            // Get a transaction up to the 4KB boundary
            uint32_t size_to_boundary = 4096 - (src_addr & 0xFFF);
            transactions.push_back(std::make_tuple(src_addr, size_to_boundary, data));
            // Divide the rest of the transactions into 4KB chunks or less
            uint64_t current_addr = src_addr + size_to_boundary;
            while (current_addr < src_addr + size)
            {
                uint32_t current_size = 4096;
                if (src_addr + size - current_addr < 4096)
                {
                    current_size = src_addr + size - current_addr;
                }
                transactions.push_back(std::make_tuple(current_addr, current_size, data + current_addr - src_addr));
                current_addr += 4096;
            }
        }
        else
        {
            transactions.push_back(std::make_tuple(src_addr, size, data));
        }
        size_t offset = 0;
        for (int j = 0; j < transactions.size(); j++)
        {
            auto transaction = transactions[j];
            uint8_t *transaction_holder = new uint8_t[4096];

            uint32_t transaction_size = std::get<1>(transaction);
            uint32_t burst_length = (transaction_size / (1 << 6)) - 1;
            uint32_t remaining_bytes = transaction_size % (1 << 6);

            if (burst_length == -1 && remaining_bytes > 0)
            {
                burst_length = 0;
            }
            else if (remaining_bytes > 0)
            {
                burst_length += 1;
            }

            if (!(burst_length < 256))
            {
                std::cout << "Error: " << std::endl;
                std::cout << "burst_length: " << burst_length << std::endl;
                std::cout << "transaction_size: " << transaction_size << std::endl;
                std::cout << "remaining_bytes: " << remaining_bytes << std::endl;
            }

            S_AXI_READ_MEM(
                std::get<0>(transaction),
                reinterpret_cast<svBitVecVal *>(transaction_holder),
                burst_length,
                6);

            memcpy(data + offset, transaction_holder, transaction_size);
            // std::cout << "Copied transaction to address: " << (uint64_t)std::get<2>(transaction) << std::endl;
            delete[] transaction_holder;
            offset += transaction_size;
            // // Log the std::get<2>(transaction) address
            // std::cout << "Address CPU: " << (uint64_t)std::get<2>(transaction) << std::endl;
            // std::cout << "Data Address: " << (uint64_t)data << std::endl;
        }

        // Copy the data from the local buffer
        memcpy(dest, data, size);
        delete[] data;
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

    ~questaMemory()
    {
    }
};
