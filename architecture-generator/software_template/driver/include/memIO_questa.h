#pragma once

#include <memIO.h>
#include <sc_dpiheader_questa.h>
#include <vector>
#include <utility>
#include <systemc.h>
#include<cmath>


// freedMemBlock now lives in memIO.h (shared by the driver and every Memory backend)


struct questaMemory : Memory
{
    questaMemory()
    {
    }

    // The QuestaSim backend is the only one that runs against a cycle-accurate
    // model, so it is the only one that can answer this meaningfully. Reported
    // in ns to match sc_time's resolution here; the driver converts to cycles
    // using the design's target frequency.
    uint64_t simTimeNs() const override
    {
        return static_cast<uint64_t>(sc_core::sc_time_stamp().to_seconds() * 1e9);
    }

    private:
    uint64_t free_mem_base_addr = 0x0;
    std::vector<freedMemBlock> freed_mem_blocks;
    std::vector<std::pair<uint64_t, uint64_t>> trackMalloc;

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
            // AWSIZE only says how many bytes a beat carries; it does not reposition
            // the payload. The VIP packs WData starting at the 2^i-aligned lane but
            // drives WSTRB from the exact address, so a burst whose start address is
            // not aligned to 2^i lands the bytes on the wrong lanes and silently
            // drops part of the buffer. Pick the largest beat size the *current
            // address* is aligned to and that still fits in what is left.
            while (transaction_size > 0)
            {
                uint64_t fpga_address = std::get<0>(transaction) + offset;
                uint64_t cpu_address = (uint64_t)std::get<2>(transaction) + offset;

                int i = 6;
                while (i > 0 &&
                       ((fpga_address & ((1ULL << i) - 1)) != 0 ||
                        transaction_size < (1u << i)))
                {
                    i--;
                }

                const uint32_t beat_bytes = 1u << i;      // i == 0 always qualifies,
                uint32_t beats = transaction_size / beat_bytes; // so this makes progress
                if (beats > 256)
                {
                    beats = 256;                          // AWLEN is 8 bits
                }

                if (!(cpu_address + beats * beat_bytes <= (uint64_t)data_tmp + size) ||
                    !(fpga_address + beats * beat_bytes <= dest_addr + size))
                {
                    std::cout << "Error: " << std::endl;
                    std::cout << "beats: " << beats << std::endl;
                    std::cout << "transaction_size: " << transaction_size << std::endl;
                    std::cout << "i: " << i << std::endl;
                    std::cout << "offset: " << offset << std::endl;
                }

                //std::cout << "Transaction Address to FPGA: " << std::hex << fpga_address << ", Size: " << transaction_size << ", Data address from the CPU: " << std::hex << cpu_address << std::endl << std::dec;

                S_AXI_WRITE_MEM(
                    fpga_address,
                    reinterpret_cast<svBitVecVal *>(cpu_address),
                    beats - 1,
                    i);

                const uint32_t done = beats * beat_bytes;
                offset += done;
                transaction_size -= done;
            }
        }
    }

    void copyFromDevice(uint8_t *dest, uint64_t src_addr, uint64_t size)
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
            uint8_t *transaction_holder = new uint8_t[4096 + 128];

            uint32_t transaction_size = std::get<1>(transaction);

            // Same lane rule as copyToDevice: with ARSIZE=6 the VIP returns data
            // starting at the 64-byte-aligned lane, so an unaligned start address
            // would come back shifted. Read from the aligned base that covers the
            // range and skip the leading bytes when copying out.
            uint64_t read_addr = std::get<0>(transaction) & ~UINT64_C(63);
            uint32_t lead = static_cast<uint32_t>(std::get<0>(transaction) - read_addr);
            uint32_t read_size = transaction_size + lead;

            uint32_t burst_length = (read_size / (1 << 6)) - 1;
            uint32_t remaining_bytes = read_size % (1 << 6);

            if (burst_length == 0xFFFFFFFFu && remaining_bytes > 0)
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
                read_addr,
                reinterpret_cast<svBitVecVal *>(transaction_holder),
                burst_length,
                6);

            memcpy(data + offset, transaction_holder + lead, transaction_size);
            // std::cout << "Copied transaction to address: " << (uint64_t)std::get<2>(transaction) << std::endl;
            delete[] transaction_holder;
            offset += transaction_size;
            // // Log the std::get<2>(transaction) address
            // std::cout << "Address CPU: " << (uint64_t)std::get<2>(transaction) << std::endl;
            // std::cout << "Data Address: " << (uint64_t)data << std::endl;
        }

        // Copy the data from the local buffer
        memcpy(dest, data, size);
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