#pragma once

#include <memIO.h>
#include <sc_dpiheader_questa.h>
#include <vector>
#include <utility>
#include <systemc.h>

struct questaMemory : Memory
{
    questaMemory()
    {
    }

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

    void copyToDevice(uint64_t dest_addr, uint8_t const *src, uint32_t size)
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

            for (uint32_t i = 6; i >= 0; i--)
            {
                // burst length is byte size of the transaction divide by 2^burst_size
                uint32_t offset = 0;
                uint32_t burst_length = (transaction_size / (1 << i)) - 1;
                uint32_t remaining_bytes = transaction_size % (1 << i);

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

                S_AXI_WRITE_MEM(
                    std::get<0>(transaction) + offset,
                    reinterpret_cast<svBitVecVal *>(std::get<2>(transaction) + offset),
                    burst_length,
                    i);

                if (remaining_bytes == 0)
                {
                    break;
                }
                else
                {
                    offset += burst_length * (1 << i);
                    transaction_size = remaining_bytes;
                }
            }
        }
    }

    void copyFromDevice(uint8_t *dest, uint64_t src_addr, uint32_t size)
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

            memcpy(data + j * 4096, transaction_holder, transaction_size);
            // std::cout << "Copied transaction to address: " << (uint64_t)std::get<2>(transaction) << std::endl;
            delete[] transaction_holder;

            // // Log the std::get<2>(transaction) address
            // std::cout << "Address CPU: " << (uint64_t)std::get<2>(transaction) << std::endl;
            // std::cout << "Data Address: " << (uint64_t)data << std::endl;
        }

        // Copy the data from the local buffer
        memcpy(dest, data, size);
    }

    ~questaMemory()
    {
    }
};