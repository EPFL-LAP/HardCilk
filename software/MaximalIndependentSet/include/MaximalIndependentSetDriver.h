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
  uint32_t stall_rounds; // 104 consecutive zero-delta rounds seen by the launcher
  uint32_t rounds;       // 108 loop rounds spawned by the launcher
  uint8_t _padding[16];
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
    // Self-check: audit the reference against itself. A correct MIS must score
    // 0/0, so a non-zero count here means the audit is wrong, not the kernel.
    auditMisResult(G, priority, ref.in_mis, ref.in_mis);
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
    // covered[] separates the two causes of a non-maximal result: a vertex left
    // uncovered was never decided at all (the loop stopped early), whereas a
    // covered-but-unmarked vertex with no marked neighbour was excluded by
    // something that is not in the set.
    std::vector<uint8_t> cov(G.num_vertices, 0);
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(cov.data()),
                            covered_base, cov.size());
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
              << " rounds=" << cont.rounds
              << " done=" << cont.done
              << " checksum=0x" << std::hex << fpga_checksum << std::dec
              << "\n";
    std::cout << "[MIS-GBBS-style] seeded reference size=" << ref_size
              << " rounds=" << ref.rounds
              << " last_covered_length=" << ref.last_covered_length
              << " checksum=0x" << std::hex << ref_checksum << std::dec
              << " execution time: " << ref.seconds << "s\n";

    auditMisResult(G, priority, got, ref.in_mis, &cov);

    if (rc == 0 && mismatches == 0 && cont.num_finished == G.num_vertices)
    {
      std::cout << "[MIS] PASS\n";
      return 0;
    }
    std::cerr << "[MIS] FAIL mismatches=" << mismatches << "\n";
    return 1;
  }

private:
  // Structural audit of the FPGA's set on its own terms, not against the
  // reference. The seeded-greedy MIS is uniquely determined by the priority
  // order, so any mismatch is an implementation bug -- but the two candidate
  // bugs leave different fingerprints:
  //
  //   * A root that lost its atomic claim on covered[] but still expanded and
  //     covered its neighbours only ever *excludes* vertices wrongly. The set
  //     stays independent, but is no longer maximal.
  //   * A nghCount[] that reached 0 before a higher-priority neighbour had
  //     finished excluding the vertex lets an adjacent pair both be marked. The
  //     set is then not independent.
  //
  // So "independent but not maximal" and "not independent" point at different
  // fixes, and neither is visible in a bare mismatch list.
  static void auditMisResult(const UnweightedGraph &G,
                             const std::vector<uint32_t> &priority,
                             const std::vector<uint8_t> &got,
                             const std::vector<uint8_t> &ref,
                             const std::vector<uint8_t> *covered = nullptr)
  {
    uint64_t bad_edges = 0;
    uint64_t not_maximal = 0;
    uint64_t rule_violations = 0; // unmarked, but every marked ngh is LOWER prio
    uint32_t rule_shown = 0;
    uint64_t not_maximal_undecided = 0; // covered[v]==0: never decided
    uint64_t not_maximal_excluded = 0;  // covered[v]==1: excluded by a non-member
    uint64_t uncovered_total = 0;
    uint32_t bad_edges_shown = 0;
    uint32_t not_maximal_shown = 0;

    if (covered)
      for (uint32_t v = 0; v < G.num_vertices; v++)
        uncovered_total += ((*covered)[v] == 0);

    for (uint32_t v = 0; v < G.num_vertices; v++)
    {
      const bool marked = got[v] != 0;
      bool has_marked_neighbour = false;
      bool has_higher_marked_neighbour = false;

      for (uint32_t i = G.offsets[v]; i < G.offsets[v + 1]; i++)
      {
        const uint32_t u = G.neighbors[i];
        if (u == v || got[u] == 0)
          continue;
        has_marked_neighbour = true;
        if (priority[u] > priority[v])
          has_higher_marked_neighbour = true;
        if (!marked || u < v)
          continue; // count each violating edge once
        bad_edges++;
        if (bad_edges_shown < 5)
        {
          std::cerr << "[MIS-AUDIT] not independent: v=" << v
                    << " (prio=" << priority[v]
                    << " ref=" << (uint32_t)ref[v] << ")"
                    << " -- u=" << u << " (prio=" << priority[u]
                    << " ref=" << (uint32_t)ref[u] << ")\n";
          bad_edges_shown++;
        }
      }

      // The defining rule: v is out of the set iff a HIGHER-priority neighbour
      // is in it. An unmarked vertex whose marked neighbours are all lower
      // priority was wrongly excluded -- something not entitled to do so
      // covered it. This is the check that separates "a valid MIS" from "THE
      // seeded MIS"; independence + maximality alone do not, since a graph has
      // many maximal independent sets.
      if (!marked && !has_higher_marked_neighbour)
      {
        rule_violations++;
        if (rule_shown < 5)
        {
          std::cerr << "[MIS-AUDIT] rule violation: v=" << v
                    << " (prio=" << priority[v] << " ref=" << (uint32_t)ref[v]
                    << " degree=" << G.degree(v)
                    << ") is unmarked with no higher-priority marked neighbour\n";
          rule_shown++;
        }
      }

      if (!marked && !has_marked_neighbour)
      {
        not_maximal++;
        const bool undecided = covered && (*covered)[v] == 0;
        if (covered)
        {
          if (undecided)
            not_maximal_undecided++;
          else
            not_maximal_excluded++;
        }
        if (not_maximal_shown < 5)
        {
          std::cerr << "[MIS-AUDIT] not maximal: v=" << v
                    << " (prio=" << priority[v]
                    << " ref=" << (uint32_t)ref[v]
                    << " degree=" << G.degree(v);
          if (covered)
            std::cerr << " covered=" << (uint32_t)(*covered)[v];
          std::cerr << ")\n";
          not_maximal_shown++;
        }
      }
    }

    std::cout << "[MIS-AUDIT] independence violations (edges with both ends in "
                 "the set): "
              << bad_edges << "\n";
    std::cout << "[MIS-AUDIT] maximality violations (unmarked with no marked "
                 "neighbour): "
              << not_maximal << "\n";
    if (covered)
      std::cout << "[MIS-AUDIT] uncovered vertices (never decided): "
                << uncovered_total << "; of the maximality violations, "
                << not_maximal_undecided << " undecided / "
                << not_maximal_excluded << " excluded-by-a-non-member\n";

    std::cout << "[MIS-AUDIT] rule violations (unmarked with no higher-priority "
                 "marked neighbour): "
              << rule_violations << "\n";

    if (bad_edges == 0 && not_maximal == 0 && rule_violations != 0)
      std::cout << "[MIS-AUDIT] verdict: a VALID maximal independent set, but "
                   "not the seeded one -> vertices were excluded by something "
                   "not entitled to exclude them (ungated expansion)\n";
    else if (bad_edges != 0)
      std::cout << "[MIS-AUDIT] verdict: NOT independent -> nghCount reached 0 "
                   "before a higher-priority neighbour had finished excluding "
                   "the vertex\n";
    else if (not_maximal != 0 && covered && not_maximal_undecided >= not_maximal_excluded)
      std::cout << "[MIS-AUDIT] verdict: independent, NOT maximal, mostly "
                   "UNDECIDED -> the round loop stopped with work outstanding "
                   "(nextFChar / last_covered_length), not an expansion bug\n";
    else if (not_maximal != 0)
      std::cout << "[MIS-AUDIT] verdict: independent, NOT maximal, mostly "
                   "EXCLUDED -> something not in the set covered them: a root "
                   "that lost its covered[] claim still expanded\n";
    else
      std::cout << "[MIS-AUDIT] verdict: matches the seeded MIS (independent, "
                   "maximal, and every exclusion justified by a higher-priority "
                   "member)\n";
  }

  std::string graph_file_;
  uint32_t seed_;
};
