#pragma once

#include <GraphBenchmarkCommon.h>
#include <benchmarks/MaximalIndependentSet/RandomGreedy/MaximalIndependentSet.h>

#include <cstddef>

struct MISHost_args
{
  uint32_t counter;
  uint32_t vertex_count;
  uint32_t ngs_done;
  uint32_t active;
  uint32_t done;
  uint32_t num_finished;
  uint32_t last_covered_length;
  uint32_t loop_started;
  Addr graph;
  Addr priority;
  Addr nghCount;
  Addr covered;
  Addr inMis;
  Addr covered0;
  Addr covered1;
  Addr nextFChar;
  Addr cont;
  uint8_t _padding[24];
};
static_assert(sizeof(MISHost_args) == 128, "MISHost_args must be 128 bytes");

class MaximalIndependentSetDriver : public BenchmarkDriverBase
{
public:
  MaximalIndependentSetDriver(Memory *memory, const std::string &graph_file,
                              uint32_t seed = 1,
                              double watchdog_s = 600.0,
                              bool fast_mode = false)
      : BenchmarkDriverBase(memory, watchdog_s, fast_mode,
                            "MaximalIndependentSet"),
        graph_file_(graph_file), seed_(seed) {}

  static int run_cpu_test_bench(const std::string &graph_file,
                                uint32_t seed = 1)
  {
    UnweightedGraph G;
    if (!loadUndirectedGraph(graph_file, G))
      return 1;
    std::vector<uint32_t> priority = seededPermutation(G.num_vertices, seed);
    MISReference ref = runSeededMISReference(G, priority);
    uint64_t ref_size = 0;
    uint64_t ref_checksum = summarizeBitset(ref.in_mis, ref_size);
    std::cout << "[MIS-CPU] vertices=" << G.num_vertices
              << " input_edges=" << G.num_edges << " seed=" << seed << "\n";
    std::cout << "[MIS-GBBS-style] seeded reference size=" << ref_size
              << " rounds=" << ref.rounds
              << " last_covered_length=" << ref.last_covered_length
              << " checksum=0x" << std::hex << ref_checksum << std::dec
              << " execution time: " << ref.seconds << "s\n";
    return 0;
  }

  int run_test_bench() override
  {
    auto t0 = std::chrono::high_resolution_clock::now();
    UnweightedGraph G;
    if (!loadUndirectedGraph(graph_file_, G))
      return 1;
    if (G.num_vertices == 0)
    {
      std::cerr << "[MIS] empty graph\n";
      return 1;
    }
    std::cout << "[MIS] vertices=" << G.num_vertices
              << " input_edges=" << G.num_edges << " seed=" << seed_ << "\n";

    std::vector<uint32_t> priority = seededPermutation(G.num_vertices, seed_);
    MISReference ref = runSeededMISReference(G, priority);

    Addr neighbors_base = 0;
    Addr graph_base = writeUnweightedCsrToHbm(memory_, G, neighbors_base);
    Addr priority_base =
        memory_->allocateMemFPGA((uint64_t)G.num_vertices * sizeof(uint32_t),
                                 512);
    Addr count_base =
        memory_->allocateMemFPGA((uint64_t)G.num_vertices * sizeof(uint32_t),
                                 512);
    Addr covered_base = memory_->allocateMemFPGA(G.num_vertices, 512);
    Addr in_mis_base = memory_->allocateMemFPGA(G.num_vertices, 512);
    Addr covered0_base =
        memory_->allocateMemFPGA((uint64_t)G.num_vertices * sizeof(uint32_t),
                                 512);
    Addr covered1_base =
        memory_->allocateMemFPGA((uint64_t)G.num_vertices * sizeof(uint32_t),
                                 512);
    Addr nextFChar_base = memory_->allocateMemFPGA(sizeof(uint64_t), 512);
    Addr cont_base = memory_->allocateMemFPGA(sizeof(MISHost_args), 512);

    std::vector<uint32_t> zeros32(G.num_vertices, 0);
    std::vector<uint8_t> zeros8(G.num_vertices, 0);
    uint64_t zero64 = 0;
    copyVectorToDevice(memory_, priority_base, priority);
    copyVectorToDevice(memory_, count_base, zeros32);
    copyVectorToDevice(memory_, covered_base, zeros8);
    copyVectorToDevice(memory_, in_mis_base, zeros8);
    copyBytesToDevice(memory_, nextFChar_base, &zero64, sizeof(zero64));

    MISHost_args root{};
    root.vertex_count = G.num_vertices;
    root.graph = graph_base;
    root.priority = priority_base;
    root.nghCount = count_base;
    root.covered = covered_base;
    root.inMis = in_mis_base;
    root.covered0 = covered0_base;
    root.covered1 = covered1_base;
    root.nextFChar = nextFChar_base;
    root.cont = cont_base;
    copyBytesToDevice(memory_, cont_base, &root, sizeof(root));

    tuneSchedulerQueueCapacities("MaximalIndependentSet", G.num_vertices);
    auto t_kernel_start = std::chrono::high_resolution_clock::now();
    int rc = runRootTask(std::vector<MISHost_args>{root}, cont_base,
                         offsetof(MISHost_args, done));
    auto t_kernel_done = t_kernel_done_;

    std::vector<uint8_t> got(G.num_vertices, 0);
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(got.data()),
                            in_mis_base, got.size());
    MISHost_args cont{};
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(&cont), cont_base,
                            sizeof(cont));
    auto t_result = std::chrono::high_resolution_clock::now();

    uint32_t mismatches = 0;
    for (uint32_t v = 0; v < G.num_vertices; v++)
    {
      if (got[v] != ref.in_mis[v])
      {
        if (mismatches < 20)
          std::cerr << "[MIS] MISMATCH v=" << v << " fpga="
                    << (uint32_t)got[v]
                    << " gbbs_seeded=" << (uint32_t)ref.in_mis[v] << "\n";
        mismatches++;
      }
    }

    uint64_t fpga_size = 0;
    uint64_t ref_size = 0;
    uint64_t fpga_checksum = summarizeBitset(got, fpga_size);
    uint64_t ref_checksum = summarizeBitset(ref.in_mis, ref_size);

    std::cout << "[MIS-FPGA] execution time: "
              << std::chrono::duration<double>(t_kernel_done - t_kernel_start)
                     .count()
              << "s\n";
    std::cout << "[MIS-FPGA] end-to-end time: "
              << std::chrono::duration<double>(t_result - t0).count() << "s\n";
    std::cout << "[MIS-FPGA] size=" << fpga_size
              << " num_finished=" << cont.num_finished
              << " last_covered_length=" << cont.last_covered_length
              << " done=" << cont.done
              << " checksum=0x" << std::hex << fpga_checksum << std::dec
              << "\n";
    std::cout << "[MIS-GBBS-style] seeded reference size=" << ref_size
              << " rounds=" << ref.rounds
              << " last_covered_length=" << ref.last_covered_length
              << " checksum=0x" << std::hex << ref_checksum << std::dec
              << " execution time: " << ref.seconds << "s\n";

    if (rc == 0 && mismatches == 0 && cont.num_finished == G.num_vertices)
    {
      std::cout << "[MIS] PASS\n";
      return 0;
    }
    std::cerr << "[MIS] FAIL mismatches=" << mismatches << "\n";
    return 1;
  }

private:
  std::string graph_file_;
  uint32_t seed_;
};
