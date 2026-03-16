#pragma once

// ─────────────────────────────────────────────────────────────────────────────
// fpga_bench.h  –  shared infrastructure for multi-FPGA graph benchmarks
//
// Each benchmark main.cpp only needs to:
//   1.  #include "fpga_bench.h"
//   2.  Define a KERNEL_NAME string
//   3.  Call run_benchmark<DriverClass>(argc, argv, KERNEL_NAME)
// ─────────────────────────────────────────────────────────────────────────────

#include <memIO_xrt.h>
#include <experimental/xrt_xclbin.h>
#include <experimental/xrt_ip.h>
#include <vnx/cmac.hpp>
#include <xrt/xrt_device.h>

#include <chrono>
#include <cstdlib>
#include <iostream>
#include <string>
#include <thread>
#include <vector>

// ─────────────────────────────────────────────────────────────────────────────
// Argument parsing
// ─────────────────────────────────────────────────────────────────────────────

struct BenchArgs {
    std::string xclbin_path;
    std::string graph_file;
    bool        enable_vnx  = false;
    int         fpga_count  = 2;
};

inline void print_usage(const char* prog)
{
    std::cerr
        << "\nUsage:\n"
        << "  " << prog << " <xclbin_path> <graph_file> <enable_vnx> [fpga_count]\n\n"
        << "Arguments:\n"
        << "  xclbin_path   Path to the .xclbin bitstream to load onto each FPGA\n"
        << "  graph_file    Path to the input graph file for the kernel\n"
        << "  enable_vnx    1 = bring up VNx Ethernet links, 0 = skip network init\n"
        << "  fpga_count    (optional) number of FPGAs to use [default: 2]\n\n"
        << "Examples:\n"
        << "  " << prog << " kernel.xclbin graph.bin 1\n"
        << "  " << prog << " kernel.xclbin graph.bin 0 1\n\n";
}

inline bool parse_args(int argc, char* argv[], BenchArgs& out)
{
    if (argc < 4 || argc > 5) {
        print_usage(argv[0]);
        return false;
    }

    out.xclbin_path = argv[1];
    out.graph_file  = argv[2];

    const int vnx_flag = std::atoi(argv[3]);
    if (vnx_flag != 0 && vnx_flag != 1) {
        std::cerr << "[ERROR] enable_vnx must be 0 or 1, got: " << argv[3] << "\n";
        return false;
    }
    out.enable_vnx = (vnx_flag == 1);

    if (argc == 5) {
        out.fpga_count = std::atoi(argv[4]);
        if (out.fpga_count < 1) {
            std::cerr << "[ERROR] fpga_count must be >= 1, got: " << argv[4] << "\n";
            return false;
        }
    }

    return true;
}

// ─────────────────────────────────────────────────────────────────────────────
// FPGA context  –  owns all XRT / VNx handles for the lifetime of a run
// ─────────────────────────────────────────────────────────────────────────────

struct FpgaContext {
    std::vector<xrt::device>  fpgas;
    std::vector<xrt::uuid>    uuids;
    std::vector<vnx::CMAC>    cmacs;
    std::vector<xrt::ip>      kernels;
    std::vector<XRTMemory>    xrt_memories;   // owns the memory objects
    std::vector<Memory*>      memories;       // raw view for drivers

    explicit FpgaContext(int n) {
        fpgas.resize(n);
        uuids.resize(n);
        xrt_memories.reserve(n);   // must not reallocate; ptrs in memories[] rely on this
    }
};

inline FpgaContext init_fpgas(const BenchArgs& args, const std::string& kernel_name)
{
    FpgaContext ctx(args.fpga_count);

    std::cout << "[Init] Loading '" << args.xclbin_path
              << "' onto " << args.fpga_count << " FPGA(s)...\n";

    for (int i = 0; i < args.fpga_count; ++i) {
        ctx.fpgas[i] = xrt::device(i);
        ctx.uuids[i] = ctx.fpgas[i].load_xclbin(args.xclbin_path);
        ctx.cmacs.emplace_back(xrt::ip(ctx.fpgas[i], ctx.uuids[i], "cmac_0"));
        ctx.kernels.emplace_back(ctx.fpgas[i], ctx.uuids[i], kernel_name);
        std::cout << "[Init] FPGA " << i << " ready.\n";
    }

    for (int i = 0; i < args.fpga_count; ++i) {
        ctx.xrt_memories.emplace_back(ctx.fpgas[i], ctx.kernels[i]);
        ctx.memories.push_back(&ctx.xrt_memories[i]);
    }

    return ctx;
}

// ─────────────────────────────────────────────────────────────────────────────
// VNx network bring-up
// ─────────────────────────────────────────────────────────────────────────────

inline void vnx_init_links(std::vector<vnx::CMAC>& cmacs)
{
    const int n = static_cast<int>(cmacs.size());

    std::cout << "[VNx] Enabling RS-FEC and sending init packets...\n";
    for (int i = 0; i < n; ++i) {
        cmacs[i].set_rs_fec(true);
        cmacs[i].send_init_packets();
    }

    std::cout << "[VNx] Waiting for link...\n";
    for (int i = 0; i < n; ++i) {
        bool link_up = false;
        for (int attempt = 0; attempt < 5; ++attempt) {
            link_up = cmacs[i].link_status()["rx_status"];
            if (link_up) break;
            std::this_thread::sleep_for(std::chrono::seconds(1));
        }
        std::cout << "[VNx] Interface " << i
                  << " link: "     << (link_up ? "UP"      : "DOWN")
                  << " | RS-FEC: " << (cmacs[i].get_rs_fec() ? "enabled" : "disabled")
                  << "\n";
    }

    std::cout << "[VNx] Settling link (10 s)...\n";
    std::this_thread::sleep_for(std::chrono::seconds(10));

    for (int i = 0; i < n; ++i) {
        cmacs[i].stop_init_packets();
        cmacs[i].set_flow_control();
    }

    std::cout << "[VNx] Confirming final link status...\n";
    std::this_thread::sleep_for(std::chrono::seconds(10));
    for (int i = 0; i < n; ++i) {
        std::cout << "[VNx] CMAC " << i << " status:\n";
        for (auto& [key, val] : cmacs[i].link_status())
            std::cout << "  " << key << " = " << val << "\n";
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CMAC statistics dump
// ─────────────────────────────────────────────────────────────────────────────

inline void print_cmac_stats(std::vector<vnx::CMAC>& cmacs)
{
    for (int i = 0; i < static_cast<int>(cmacs.size()); ++i) {
        auto stats = cmacs[i].statistics(true);
        std::cout << "\n[Stats] CMAC " << i << " TX:\n";
        for (auto& [k, v] : stats.tx)
            std::cout << "  " << k << ": " << v << "\n";
        std::cout << "[Stats] CMAC " << i << " RX:\n";
        for (auto& [k, v] : stats.rx)
            std::cout << "  " << k << ": " << v << "\n";
        std::cout << "[Stats] CMAC " << i
                  << " cycle count: " << stats.cycle_count << "\n";
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Top-level entry point — call this from every benchmark main()
// ─────────────────────────────────────────────────────────────────────────────

template <typename Driver>
int run_benchmark(int argc, char* argv[], const std::string& kernel_name)
{
    BenchArgs args;
    if (!parse_args(argc, argv, args))
        return EXIT_FAILURE;

    FpgaContext ctx = init_fpgas(args, kernel_name);
    std::this_thread::sleep_for(std::chrono::seconds(1));

    if (args.enable_vnx)
        vnx_init_links(ctx.cmacs);

    std::this_thread::sleep_for(std::chrono::seconds(1));

    std::cout << "[Run] Starting " << kernel_name
              << " on " << args.fpga_count << " FPGA(s)...\n";

    Driver driver(ctx.memories, args.graph_file);
    driver.run_test_bench_mFpga();

    print_cmac_stats(ctx.cmacs);
    return EXIT_SUCCESS;
}