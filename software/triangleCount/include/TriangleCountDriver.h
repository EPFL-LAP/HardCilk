#pragma once

// Modern single-FPGA host for the original regular triangleCount HLS.  The HLS
// ABI is intentionally unchanged: vertex_map emits the historical no-cache
// {argDataOut, argOut} pair, which writes a per-vertex count and decrements the
// host-provided root continuation counter.

#include <GraphBenchmarkCommon.h>
#include <hardCilkDriver.h>
#include <benchmarks/TriangleCounting/ShunTangwongsan15/Triangle.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <numeric>
#include <string>
#include <thread>
#include <tuple>
#include <utility>
#include <vector>

using Addr = uint64_t;

struct TriangleCountRootTask
{
  uint32_t counter;
  uint32_t vertex_count;
  Addr triangle_count_arr;
  Addr adj_list;
  Addr cont;
};

static_assert(sizeof(TriangleCountRootTask) == 32,
              "triangle root task must match triangle_args in util.h");

inline bool triangleCountDoneCondition(int32_t value) { return value == 1; }

class TriangleCountDriver : public hardCilkDriver
{
public:
  TriangleCountDriver(Memory *memory, std::string graph_path,
                      double watchdog_s = 600.0, bool fast_mode = false)
      : hardCilkDriver(memory), graph_path_(std::move(graph_path)),
        watchdog_s_(watchdog_s), fast_mode_(fast_mode) {}

  static int run_cpu_test_bench(const std::string &graph_path)
  {
    UnweightedGraph undirected;
    if (!loadAndConditionGraph(graph_path, undirected))
      return 1;

    const GbbsTriangleResult gbbs = runTimedOfficialGBBSTriangle(undirected);
    const OrientedGraph oriented = orientByDegree(undirected);
    const uint64_t oriented_count = referenceOrientedCount(oriented);

    std::cout << "[triangleCount-CPU] vertices=" << undirected.num_vertices
              << " directed_edges=" << undirected.offsets.back()
              << " oriented_edges=" << oriented.neighbors.size()
              << " triangles=" << oriented_count << "\n";
    std::cout << "[triangleCount-GBBS] triangles=" << gbbs.triangles
              << " execution time: " << gbbs.count_s << "s (graph build "
              << gbbs.graph_build_s << "s)\n";
    return oriented_count == gbbs.triangles ? 0 : 1;
  }

  int run_test_bench() override
  {
    const auto t0 = std::chrono::high_resolution_clock::now();
    const auto t_load = t0;

    std::cout << "[triangleCount] loading " << graph_path_ << std::endl;
    UnweightedGraph undirected;
    if (!loadAndConditionGraph(graph_path_, undirected))
      return 1;
    const auto t_graph_loaded = std::chrono::high_resolution_clock::now();
    const uint32_t n = undirected.num_vertices;
    if (n == 0)
    {
      std::cerr << "[triangleCount] empty graph, aborting\n";
      return 1;
    }

    const double graph_load_s =
        std::chrono::duration<double>(t_graph_loaded - t_load).count();
    std::cout << "[triangleCount] graph=" << graph_path_
              << " vertices=" << n
              << " directed_edges=" << undirected.offsets.back() << "\n";

    std::cout << "[triangleCount] running the GBBS reference" << std::endl;
    const GbbsTriangleResult gbbs = runTimedOfficialGBBSTriangle(undirected);
    std::cout << "[triangleCount-GBBS] execution time: " << gbbs.count_s
              << "s\n";
    std::cout << "[triangleCount-GBBS] end-to-end time: "
              << (graph_load_s + gbbs.total_s) << "s\n";
    std::cout << "[triangleCount-GBBS] triangles=" << gbbs.triangles << "\n";

    // This is the same degree/tie-break orientation used by the old Graph +
    // filterGraph driver.  It makes every triangle appear in exactly one
    // intersection while leaving the HLS merge algorithm untouched.
    const OrientedGraph directed = orientByDegree(undirected);
    const uint32_t active_vertices = directed.nonempty_vertices;
    std::cout << "[triangleCount] oriented_edges=" << directed.neighbors.size()
              << " active_vertices=" << active_vertices << "/" << n << "\n";

    // Preserve the legacy driver's low-address guard and allocation order.
    memory_->allocateMemFPGA(4096, 512);
    const Addr continuation_addr =
        memory_->allocateMemFPGA(sizeof(TriangleCountRootTask), 512);

    const uint64_t neighbor_bytes = std::max<uint64_t>(
        sizeof(uint32_t), directed.neighbors.size() * sizeof(uint32_t));
    const Addr neighbors_base = memory_->allocateMemFPGA(neighbor_bytes, 512);
    copyVectorToDevice(memory_, neighbors_base, directed.neighbors);

    std::vector<uint64_t> adjacency(2ull * n, 0);
    for (uint32_t v = 0; v < n; ++v)
    {
      adjacency[2ull * v] =
          neighbors_base + (uint64_t)directed.offsets[v] * sizeof(uint32_t);
      adjacency[2ull * v + 1] = directed.degree(v);
    }
    const Addr adjacency_base =
        memory_->allocateMemFPGA(adjacency.size() * sizeof(uint64_t), 512);
    copyVectorToDevice(memory_, adjacency_base, adjacency);

    const Addr result_base =
        memory_->allocateMemFPGA((uint64_t)n * sizeof(uint32_t), 512);
    const std::vector<uint32_t> zeros(n, 0);
    copyVectorToDevice(memory_, result_base, zeros);

    TriangleCountRootTask root{};
    // The no-cache notifier decrements once per non-empty oriented adjacency
    // list and the historical done condition is counter==1.
    root.counter = active_vertices + 1;
    root.vertex_count = n;
    root.triangle_count_arr = result_base;
    root.adj_list = adjacency_base;
    root.cont = continuation_addr;
    memory_->copyToDevice(continuation_addr,
                          reinterpret_cast<const uint8_t *>(&root),
                          sizeof(root));

    std::cout << "[triangleCount] continuation=0x" << std::hex
              << continuation_addr << " adjacency=0x" << adjacency_base
              << " results=0x" << result_base << std::dec
              << " initial_counter=" << root.counter << std::endl;

    const auto t_init_start = std::chrono::high_resolution_clock::now();
    initSystem(std::vector<TriangleCountRootTask>{root},
               &triangleCountDoneCondition, 0, 0, false);
    const auto t_init_done = std::chrono::high_resolution_clock::now();
    std::cout << "[triangleCount-FPGA] init time: "
              << std::chrono::duration<double>(t_init_done - t_init_start).count()
              << "s\n";

    const auto t_kernel_start = std::chrono::high_resolution_clock::now();
    startSystem();
    const int rc = pollCounter(continuation_addr, active_vertices,
                               t_kernel_start);
    const auto t_kernel_done = std::chrono::high_resolution_clock::now();

    std::vector<uint32_t> counts(n, 0);
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(counts.data()),
                            result_base,
                            (uint64_t)n * sizeof(uint32_t));
    const auto t_result_ready = std::chrono::high_resolution_clock::now();
    const uint64_t fpga_triangles =
        std::accumulate(counts.begin(), counts.end(), uint64_t{0});

    const double kernel_s =
        std::chrono::duration<double>(t_kernel_done - t_kernel_start).count();
    const double fpga_end_to_end_s =
        std::chrono::duration<double>(t_result_ready - t0).count();
    std::cout << "[triangleCount-FPGA] execution time: " << kernel_s << "s\n";
    std::cout << "[triangleCount-FPGA] end-to-end time: "
              << fpga_end_to_end_s << "s\n";
    if (gbbs.count_s > 0.0)
      std::cout << "[triangleCount] speedup vs GBBS: execution "
                << (gbbs.count_s / std::max(kernel_s, 1e-12))
                << "x, end-to-end "
                << ((graph_load_s + gbbs.total_s) /
                    std::max(fpga_end_to_end_s, 1e-12))
                << "x\n";
    std::cout << "[triangleCount] fpga_triangles=" << fpga_triangles
              << " gbbs_triangles=" << gbbs.triangles << "\n";

    if (rc == 0 && fpga_triangles == gbbs.triangles)
    {
      std::cout << "[triangleCount] PASS — FPGA count matches official GBBS.\n";
      return 0;
    }

    std::cerr << "[triangleCount] FAIL rc=" << rc
              << " fpga_triangles=" << fpga_triangles
              << " expected=" << gbbs.triangles << "\n";
    return 1;
  }

private:
  struct OrientedGraph
  {
    uint32_t num_vertices = 0;
    uint32_t nonempty_vertices = 0;
    std::vector<uint32_t> offsets;
    std::vector<uint32_t> neighbors;

    uint32_t degree(uint32_t v) const
    {
      return offsets[v + 1] - offsets[v];
    }
  };

  struct GbbsTriangleResult
  {
    size_t triangles = 0;
    double graph_build_s = 0.0;
    double count_s = 0.0;
    double total_s = 0.0;
  };

  static bool loadAndConditionGraph(const std::string &path,
                                    UnweightedGraph &graph)
  {
    if (!loadUndirectedGraph(path, graph))
      return false;
    if (graph.num_vertices == 0)
      return true;

    std::vector<std::vector<uint32_t>> adjacency(graph.num_vertices);
    for (uint32_t v = 0; v < graph.num_vertices; ++v)
    {
      const auto first = graph.neighbors.begin() + graph.offsets[v];
      const auto last = graph.neighbors.begin() + graph.offsets[v + 1];
      auto &list = adjacency[v];
      list.assign(first, last);
      list.erase(std::remove(list.begin(), list.end(), v), list.end());
      std::sort(list.begin(), list.end());
      list.erase(std::unique(list.begin(), list.end()), list.end());
    }

    graph.offsets.assign((size_t)graph.num_vertices + 1, 0);
    for (uint32_t v = 0; v < graph.num_vertices; ++v)
      graph.offsets[v + 1] = graph.offsets[v] + adjacency[v].size();
    graph.neighbors.clear();
    graph.neighbors.reserve(graph.offsets.back());
    for (const auto &list : adjacency)
      graph.neighbors.insert(graph.neighbors.end(), list.begin(), list.end());
    graph.num_edges = graph.offsets.back() / 2;
    return true;
  }

  static OrientedGraph orientByDegree(const UnweightedGraph &graph)
  {
    OrientedGraph out;
    out.num_vertices = graph.num_vertices;
    out.offsets.assign((size_t)graph.num_vertices + 1, 0);
    for (uint32_t v = 0; v < graph.num_vertices; ++v)
    {
      const uint32_t degree_v = graph.degree(v);
      for (uint32_t i = graph.offsets[v]; i < graph.offsets[v + 1]; ++i)
      {
        const uint32_t u = graph.neighbors[i];
        const uint32_t degree_u = graph.degree(u);
        if (degree_v < degree_u || (degree_v == degree_u && v < u))
          out.neighbors.push_back(u);
      }
      out.offsets[v + 1] = out.neighbors.size();
      if (out.offsets[v + 1] != out.offsets[v])
        ++out.nonempty_vertices;
    }
    return out;
  }

  static uint64_t referenceOrientedCount(const OrientedGraph &graph)
  {
    uint64_t total = 0;
    for (uint32_t v = 0; v < graph.num_vertices; ++v)
    {
      for (uint32_t edge = graph.offsets[v]; edge < graph.offsets[v + 1];
           ++edge)
      {
        const uint32_t u = graph.neighbors[edge];
        uint32_t a = graph.offsets[v];
        uint32_t b = graph.offsets[u];
        while (a < graph.offsets[v + 1] && b < graph.offsets[u + 1])
        {
          if (graph.neighbors[a] < graph.neighbors[b])
            ++a;
          else if (graph.neighbors[b] < graph.neighbors[a])
            ++b;
          else
          {
            ++total;
            ++a;
            ++b;
          }
        }
      }
    }
    return total;
  }

  static GbbsTriangleResult
  runTimedOfficialGBBSTriangle(const UnweightedGraph &graph)
  {
    GbbsTriangleResult result;
    const auto t0 = std::chrono::high_resolution_clock::now();
    if (graph.num_vertices == 0)
      return result;

    using Edge = std::tuple<gbbs::uintE, gbbs::uintE, gbbs::empty>;
    auto edges = gbbs::sequence<Edge>::uninitialized(graph.offsets.back());
    size_t out = 0;
    for (uint32_t u = 0; u < graph.num_vertices; ++u)
      for (uint32_t i = graph.offsets[u]; i < graph.offsets[u + 1]; ++i)
        edges[out++] =
            Edge{(gbbs::uintE)u, (gbbs::uintE)graph.neighbors[i], gbbs::empty{}};

    auto gbbs_graph =
        gbbs::symmetric_graph<gbbs::symmetric_vertex, gbbs::empty>::from_edges(
            edges, graph.num_vertices);
    const auto t_graph_done = std::chrono::high_resolution_clock::now();
    {
      ScopedCoutSilencer silence;
      auto noop = [](gbbs::uintE, gbbs::uintE, gbbs::uintE) {};
      result.triangles = gbbs::Triangle_degree_ordering(gbbs_graph, noop);
    }
    const auto t_done = std::chrono::high_resolution_clock::now();
    result.graph_build_s =
        std::chrono::duration<double>(t_graph_done - t0).count();
    result.count_s =
        std::chrono::duration<double>(t_done - t_graph_done).count();
    result.total_s = std::chrono::duration<double>(t_done - t0).count();
    return result;
  }

  int pollCounter(Addr continuation_addr, uint32_t total_tasks,
                  std::chrono::high_resolution_clock::time_point start)
  {
    const auto deadline = start + std::chrono::duration<double>(watchdog_s_);
    const uint32_t initial_counter = total_tasks + 1;
    uint32_t counter = initial_counter;
    uint32_t last_retired = 0;
    auto last_progress = start;
    auto next_status = start + std::chrono::seconds(5);
    auto interval = fast_mode_ ? std::chrono::microseconds(10000)
                               : std::chrono::microseconds(200);
    const auto max_interval = fast_mode_ ? std::chrono::microseconds(10000)
                                         : std::chrono::microseconds(250000);

    while (true)
    {
      if (stopRequested())
      {
        std::cerr << "[triangleCount] interrupted by user; " << last_retired
                  << "/" << total_tasks << " vertex tasks retired\n";
        return 2;
      }

      if (checkPaused() == 0)
        managePausedServer();

      memory_->copyFromDevice(reinterpret_cast<uint8_t *>(&counter),
                              continuation_addr, sizeof(counter));
      if (counter == 1)
      {
        if (!fast_mode_)
          std::cout << "[triangleCount] progress: " << total_tasks << "/"
                    << total_tasks << " vertex tasks retired (100%)\n";
        return 0;
      }

      const uint32_t retired =
          counter <= initial_counter ? initial_counter - counter : 0;
      const auto now = std::chrono::high_resolution_clock::now();
      if (retired != last_retired)
      {
        last_retired = retired;
        last_progress = now;
      }
      if (!fast_mode_ && now >= next_status)
      {
        const double pct = total_tasks == 0
                               ? 100.0
                               : 100.0 * retired / total_tasks;
        std::cout << "[triangleCount] progress: " << retired << "/"
                  << total_tasks << " vertex tasks retired (" << pct
                  << "%), counter=" << counter << ", t="
                  << std::chrono::duration<double>(now - start).count() << "s"
                  << std::endl;
        next_status = now + std::chrono::seconds(5);
      }
      if (now >= deadline)
      {
        std::cerr << "[triangleCount] WATCHDOG after "
                  << std::chrono::duration<double>(now - start).count() << "s: "
                  << retired << "/" << total_tasks
                  << " vertex tasks retired, no counter progress for "
                  << std::chrono::duration<double>(now - last_progress).count()
                  << "s\n";
        return 1;
      }

      std::this_thread::sleep_for(interval);
      interval = std::min(max_interval, interval * 2);
    }
  }

  std::string graph_path_;
  double watchdog_s_;
  bool fast_mode_;
};
