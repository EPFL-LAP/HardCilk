// Functional-accuracy CPU test for the HardCilk fullTriangleCountDecoupled kernels.
//
// This links the real synthesizable code from ../fullTriangleCountDecoupled.cpp
// and drives it from a plain CPU harness that stands in for the parts of the
// architecture the kernels talk to:
//
//   * an HBM byte array holding the CSR and the closure pool,
//   * a bump allocator behind closureIn (the real one never recycles either),
//   * the spawnNext write buffer: write the continuation line, then release
//     `allow` tasks from the PE's taskOutGlobal1 stream,
//   * the ArgumentServer: OR an update into an aligned slot of a continuation
//     line, decrement its join counter, and spawn the line as a task at zero,
//   * spawnNextLocal, which bypasses all of that and goes straight back to the
//     adder queue.
//
// The golden is the same quantity the kernel computes: for every vertex v and
// every neighbour u of v, the size of adj(v) intersect adj(u). Each triangle is
// therefore counted six times (three choices of base, two neighbours each),
// which is the relation checked against GBBS when built with USE_GBBS.

#include "../util.h"

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include "hls_stream.h"
#include "hls_burst_maxi.h"

void memReader(hls::burst_maxi<window_beat> mem, hls::stream<memReader_task> &taskIn,
               hls::stream<counter_continuation_update> &argOut);

void adder(hls::stream<counter_continuation> &taskIn,
           hls::stream<adder_done_continuation_update> &argOut,
           hls::stream<uint64_t> &closureIn,
           hls::stream<memReader_taskOut> &taskOutGlobal1,
           hls::stream<adder_self_spawn_next> &spawnNext,
           hls::stream<counter_continuation> &spawnNextLocal);

void adder_unit_launcher(void *mem,
                         hls::stream<adder_unit_launcher_continuation> &taskIn,
                         hls::stream<adder_unit_launcher_done_update> &argOut,
                         hls::stream<uint64_t> &closureIn,
                         hls::stream<counter_continuation> &taskOutGlobal1,
                         hls::stream<adder_unit_launcher_spawn_next> &spawnNext);

void triangle(void *mem, hls::stream<triangle_task> &taskIn,
              hls::stream<uint64_t> &closureIn,
              hls::stream<adder_unit_launcher_continuation> &taskOutGlobal,
              hls::stream<triangle_spawn_next> &spawnNext);

void vertexWriteback(void *mem,
                     hls::stream<vertex_writeback_continuation> &taskIn);

#ifdef USE_GBBS
// Defined in gbbs_reference.cpp; CSR in, distinct triangles out.
size_t gbbsTriangleCount(uint32_t num_vertices, const uint32_t *offsets,
                         const uint32_t *neighbours);
#endif

// ---------------------------------------------------------------------------
// Graph
// ---------------------------------------------------------------------------

struct Graph
{
  uint32_t num_vertices = 0;
  std::vector<std::vector<uint32_t>> adj; // sorted, no self loops, deduplicated
};

static bool parseEdge(std::string line, uint32_t &src, uint32_t &dst)
{
  const size_t comment = line.find('#');
  if (comment != std::string::npos)
    line.resize(comment);
  std::replace(line.begin(), line.end(), ',', ' ');
  std::replace(line.begin(), line.end(), '\t', ' ');
  std::istringstream iss(line);
  return (bool)(iss >> src >> dst);
}

// The kernel merges two sorted adjacency lists, so the CSR it is handed must be
// sorted. Self loops would let a vertex match itself; duplicates would inflate
// the intersection. Both are dropped here, as an undirected loader should.
static bool loadUndirected(const std::string &path, Graph &G)
{
  std::ifstream f(path);
  if (!f.is_open())
  {
    std::cerr << "[tester] cannot open graph: " << path << "\n";
    return false;
  }

  std::vector<std::pair<uint32_t, uint32_t>> edges;
  uint32_t max_vertex = 0;
  std::string line;
  while (std::getline(f, line))
  {
    uint32_t s = 0, d = 0;
    if (!parseEdge(line, s, d))
      continue;
    if (s == d)
      continue;
    max_vertex = std::max(max_vertex, std::max(s, d));
    edges.push_back({s, d});
  }

  G.num_vertices = edges.empty() ? 0 : max_vertex + 1;
  G.adj.assign(G.num_vertices, {});
  for (size_t i = 0; i < edges.size(); i++)
  {
    G.adj[edges[i].first].push_back(edges[i].second);
    G.adj[edges[i].second].push_back(edges[i].first);
  }
  for (uint32_t v = 0; v < G.num_vertices; v++)
  {
    std::sort(G.adj[v].begin(), G.adj[v].end());
    G.adj[v].erase(std::unique(G.adj[v].begin(), G.adj[v].end()), G.adj[v].end());
  }
  return true;
}

// Sum over v, over u in adj(v), of |adj(v) intersect adj(u)| -- exactly what the
// kernel accumulates, so six times the number of distinct triangles.
static uint64_t referenceWedgeClosures(const Graph &G,
                                       std::vector<uint64_t> &per_vertex)
{
  per_vertex.assign(G.num_vertices, 0);
  uint64_t total = 0;
  for (uint32_t v = 0; v < G.num_vertices; v++)
  {
    uint64_t sum = 0;
    for (size_t k = 0; k < G.adj[v].size(); k++)
    {
      const std::vector<uint32_t> &A = G.adj[v];
      const std::vector<uint32_t> &B = G.adj[G.adj[v][k]];
      size_t i = 0, j = 0;
      while (i < A.size() && j < B.size())
      {
        if (A[i] < B[j])
          i++;
        else if (B[j] < A[i])
          j++;
        else
        {
          sum++;
          i++;
          j++;
        }
      }
    }
    per_vertex[v] = sum;
    total += sum;
  }
  return total;
}

// ---------------------------------------------------------------------------
// Device memory + the runtime the kernels expect around them
// ---------------------------------------------------------------------------

struct Hbm
{
  std::vector<uint8_t> bytes;
  explicit Hbm(size_t n) : bytes(n, 0) {}
  void *base() { return bytes.data(); }
  template <typename T> T *ptr(addr_t a) { return (T *)(bytes.data() + a); }
};

int main(int argc, char **argv)
{
  const std::string graph_path =
      (argc > 1) ? argv[1] : "/beta/bradley/Graphs/tinyGraph.txt";
  const uint64_t watchdog_steps =
      (argc > 2 && argv[2][0]) ? strtoull(argv[2], nullptr, 0) : 200000000ULL;

  Graph G;
  if (!loadUndirected(graph_path, G))
    return 2;
  if (G.num_vertices == 0)
  {
    std::cerr << "[tester] empty graph\n";
    return 2;
  }

  uint64_t edge_slots = 0;
  for (uint32_t v = 0; v < G.num_vertices; v++)
    edge_slots += G.adj[v].size();

  std::vector<uint64_t> ref_per_vertex;
  const uint64_t ref_total = referenceWedgeClosures(G, ref_per_vertex);

  // ---- Device image -------------------------------------------------------
  // adj_list: {neighbours, size} per vertex, both 8 bytes, exactly as
  // triangle() and adder_unit_launcher() index it.
  const uint64_t adj_bytes = (uint64_t)G.num_vertices * 2 * sizeof(uint64_t);
  // Every neighbour array is padded by one window: the adder always fetches
  // ADDER_WINDOW elements, so the tail read of the last list runs past its end.
  const uint64_t nbr_bytes =
      (edge_slots + (uint64_t)G.num_vertices * 2 * ADDER_WINDOW) *
      sizeof(uint32_t);
  const uint64_t result_bytes = (uint64_t)G.num_vertices * sizeof(uint64_t);
  // The pool is never recycled, same as the real allocator, so size it for every
  // closure the run will ever take: one per launcher round, plus one per window
  // fetch. A merge of adj(v) against adj(u) fetches at most one window per side
  // per ADDER_WINDOW elements consumed, plus the two initial fills.
  // Plus one vertex_writeback closure per vertex: triangle takes one before it
  // spawns each launcher. This model front-loads the whole supply rather than
  // blocking, so the pool has to cover every vertex at once.
  uint64_t closures_needed = G.num_vertices;
  for (uint32_t v = 0; v < G.num_vertices; v++)
  {
    const uint64_t dv = G.adj[v].size();
    closures_needed += dv / LAUNCHER_BATCH + 2; // launcher rounds
    for (size_t k = 0; k < G.adj[v].size(); k++)
    {
      const uint64_t du = G.adj[G.adj[v][k]].size();
      closures_needed += (dv + du) / ADDER_WINDOW + 2;
    }
  }
  const uint64_t closure_bytes =
      (closures_needed + 16) * sizeof(counter_continuation);

  const addr_t adj_base = 4096;
  const addr_t nbr_base =
      (adj_base + adj_bytes + ADDER_WINDOW * sizeof(uint32_t) - 1) &
      ~(addr_t)(ADDER_WINDOW * sizeof(uint32_t) - 1);
  const addr_t result_base = nbr_base + nbr_bytes;
  const addr_t closure_base = (result_base + result_bytes + 4095) & ~(addr_t)4095;

  Hbm hbm(closure_base + closure_bytes);

  // burst_maxi indexes in windows, so every list starts on a window boundary.
  const uint64_t kWindowBytes = ADDER_WINDOW * sizeof(uint32_t);
  addr_t cursor = nbr_base;
  for (uint32_t v = 0; v < G.num_vertices; v++)
  {
    hbm.ptr<uint64_t>(adj_base)[(size_t)v * 2] = cursor;
    hbm.ptr<uint64_t>(adj_base)[(size_t)v * 2 + 1] = G.adj[v].size();
    for (size_t i = 0; i < G.adj[v].size(); i++)
      hbm.ptr<uint32_t>(cursor)[i] = G.adj[v][i];
    cursor += ((G.adj[v].size() + ADDER_WINDOW) * sizeof(uint32_t) +
               kWindowBytes - 1) &
              ~(kWindowBytes - 1);
  }

  addr_t closure_next = closure_base;
  uint64_t closures_used = 0;
  auto allocClosure = [&]() -> addr_t {
    addr_t a = closure_next;
    closure_next += sizeof(counter_continuation);
    closures_used++;
    if (closure_next > closure_base + closure_bytes)
    {
      std::cerr << "[tester] closure pool exhausted after " << closures_used
                << " allocations\n";
      std::exit(3);
    }
    return a;
  };

  // ---- Task queues --------------------------------------------------------
  std::deque<adder_unit_launcher_continuation> q_launcher;
  std::deque<counter_continuation> q_adder;
  std::deque<memReader_task> q_memreader;
  std::deque<vertex_writeback_continuation> q_writeback;

  uint64_t launcher_runs = 0, adder_runs = 0, memreader_runs = 0,
           writeback_runs = 0;

  // Which ring a resolved line is released onto. The hardware knows this from
  // the closure's type; here the caller passes it, because two of the three
  // update kinds now carry a 4-byte payload and the width alone no longer tells
  // an adder's parent from a launcher's.
  enum LineKind { LINE_ADDER, LINE_LAUNCHER, LINE_WRITEBACK };

  // The ArgumentServer: OR the payload into an aligned slot of the line, drop
  // the join counter, and spawn the line once nothing is outstanding.
  auto applyUpdate = [&](LineKind kind, addr_t address, const void *payload,
                         size_t payload_bytes, uint32_t slot) {
    uint8_t *line = hbm.ptr<uint8_t>(address);
    const uint8_t *src = (const uint8_t *)payload;
    for (size_t i = 0; i < payload_bytes; i++)
      line[slot * payload_bytes + i] |= src[i];

    uint32_t &counter = *(uint32_t *)line;
    if (counter == 0)
    {
      std::cerr << "[tester] update to a line whose counter is already 0\n";
      std::exit(3);
    }
    if (--counter == 0)
    {
      if (kind == LINE_ADDER)
      {
        counter_continuation task;
        std::memcpy(&task, line, sizeof(task));
        q_adder.push_back(task);
      }
      else if (kind == LINE_LAUNCHER)
      {
        adder_unit_launcher_continuation task;
        std::memcpy(&task, line, sizeof(task));
        q_launcher.push_back(task);
      }
      else
      {
        vertex_writeback_continuation task;
        std::memcpy(&task, line, sizeof(task));
        q_writeback.push_back(task);
      }
    }
  };

  // ---- Root ---------------------------------------------------------------
  {
    hls::stream<triangle_task> in;
    hls::stream<uint64_t> closureIn;
    hls::stream<adder_unit_launcher_continuation> out;
    hls::stream<triangle_spawn_next> park;
    triangle_task root;
    std::memset(&root, 0, sizeof(root));
    root._cont = 0;
    root.adj_list = adj_base;
    root.triangle_count_arr = result_base;
    root.vertex_count = G.num_vertices;
    in.write(root);
    // The real allocator hands these out one at a time and blocks when the pool
    // is dry -- that blocking IS the admission cap. This model runs triangle to
    // completion in one call, so it front-loads the whole supply instead; the
    // cap's effect on ordering cannot be observed from a single-threaded model,
    // only its effect on correctness, which is what this checks.
    for (uint32_t v = 0; v < G.num_vertices; v++)
      closureIn.write(allocClosure());
    triangle(hbm.base(), in, closureIn, out, park);
    // Degree-0 vertices are retired by triangle without taking a closure.
    while (!closureIn.empty())
    {
      closureIn.read();
      closures_used--;
      closure_next -= sizeof(counter_continuation);
    }
    while (!park.empty())
    {
      triangle_spawn_next p = park.read();
      std::memcpy(hbm.ptr<uint8_t>(p.addr), &p.data, sizeof(p.data));
    }
    while (!out.empty())
      q_launcher.push_back(out.read());
  }

  // ---- Run until quiet ----------------------------------------------------
  // The streams are hoisted out of the dispatch loop and drained every time
  // round: rebuilding them per call leaves HLS complaining about leftover data
  // in every closureIn the kernel decided not to read.
  hls::stream<adder_unit_launcher_continuation> l_in;
  hls::stream<adder_unit_launcher_done_update> l_argOut;
  hls::stream<uint64_t> l_closureIn;
  hls::stream<counter_continuation> l_tasks;
  hls::stream<adder_unit_launcher_spawn_next> l_pkt;

  hls::stream<vertex_writeback_continuation> w_in;

  hls::stream<counter_continuation> a_in;
  hls::stream<adder_done_continuation_update> a_argOut;
  hls::stream<uint64_t> a_closureIn;
  hls::stream<memReader_taskOut> a_tasks;
  hls::stream<adder_self_spawn_next> a_pkt;
  hls::stream<counter_continuation> a_local;

  hls::stream<memReader_task> m_in;
  hls::stream<counter_continuation_update> m_argOut;

  uint64_t steps = 0;
  bool watchdog = false;
  while (!q_launcher.empty() || !q_adder.empty() || !q_memreader.empty() ||
         !q_writeback.empty())
  {
    if (++steps > watchdog_steps)
    {
      watchdog = true;
      break;
    }

    // Drained first: it allocates nothing, retires a vertex, and in hardware is
    // what returns that vertex's admission token to the pool.
    if (!q_writeback.empty())
    {
      w_in.write(q_writeback.front());
      q_writeback.pop_front();
      vertexWriteback(hbm.base(), w_in);
      writeback_runs++;
      continue;
    }

    if (!q_launcher.empty())
    {
      auto &in = l_in; auto &closureIn = l_closureIn;
      auto &tasks = l_tasks; auto &pkt = l_pkt; auto &argOut = l_argOut;

      in.write(q_launcher.front());
      q_launcher.pop_front();
      const addr_t closure = allocClosure();
      closureIn.write(closure);
      adder_unit_launcher(hbm.base(), in, argOut, closureIn, tasks, pkt);
      launcher_runs++;

      while (!argOut.empty())
      {
        adder_unit_launcher_done_update u = argOut.read();
        const uint32_t payload = u.payload;
        applyUpdate(LINE_WRITEBACK, u.address, &payload, sizeof(payload),
                    (uint32_t)u.offset);
      }

      // A round that retires the vertex returns before taking the closure. Give
      // it back, or it stays queued and the next round reads a stale address.
      if (!closureIn.empty())
      {
        closureIn.read();
        closures_used--;
        closure_next -= sizeof(counter_continuation);
      }

      std::vector<counter_continuation> staged;
      while (!tasks.empty())
        staged.push_back(tasks.read());
      if (!pkt.empty())
      {
        adder_unit_launcher_spawn_next p = pkt.read();
        std::memcpy(hbm.ptr<uint8_t>(p.addr), &p.data, sizeof(p.data));
        if (p.allow != staged.size())
        {
          std::cerr << "[tester] launcher allow=" << p.allow << " but staged "
                    << staged.size() << " tasks\n";
          return 3;
        }
        for (size_t i = 0; i < staged.size(); i++)
          q_adder.push_back(staged[i]);
      }
      else if (!staged.empty())
      {
        std::cerr << "[tester] launcher emitted tasks with no spawnNext packet\n";
        return 3;
      }
      continue;
    }

    if (!q_adder.empty())
    {
      auto &in = a_in; auto &argOut = a_argOut; auto &closureIn = a_closureIn;
      auto &tasks = a_tasks; auto &pkt = a_pkt; auto &local = a_local;

      in.write(q_adder.front());
      q_adder.pop_front();
      const addr_t closure = allocClosure();
      closureIn.write(closure);
      adder(in, argOut, closureIn, tasks, pkt, local);
      adder_runs++;

      const bool took_closure = closureIn.empty();
      if (!took_closure)
      {
        closureIn.read();
        closures_used--;
        closure_next -= sizeof(counter_continuation);
      }

      std::vector<memReader_task> staged;
      while (!tasks.empty())
        staged.push_back(tasks.read());
      if (!pkt.empty())
      {
        adder_self_spawn_next p = pkt.read();
        std::memcpy(hbm.ptr<uint8_t>(p.addr), &p.data, sizeof(p.data));
        if (p.allow != staged.size())
        {
          std::cerr << "[tester] adder allow=" << p.allow << " but staged "
                    << staged.size() << " tasks\n";
          return 3;
        }
        for (size_t i = 0; i < staged.size(); i++)
          q_memreader.push_back(staged[i]);
      }
      else if (!staged.empty())
      {
        std::cerr << "[tester] adder emitted a memReader with no packet\n";
        return 3;
      }

      while (!local.empty())
        q_adder.push_back(local.read());
      while (!argOut.empty())
      {
        adder_done_continuation_update u = argOut.read();
        const uint32_t payload = u.payload;
        applyUpdate(LINE_LAUNCHER, u.address, &payload, sizeof(payload), (uint32_t)u.offset);
      }
      continue;
    }

    {
      auto &in = m_in; auto &argOut = m_argOut;
      in.write(q_memreader.front());
      q_memreader.pop_front();
      memReader(hls::burst_maxi<window_beat>((window_beat *)hbm.base()), in, argOut);
      memreader_runs++;
      while (!argOut.empty())
      {
        counter_continuation_update u = argOut.read();
        applyUpdate(LINE_ADDER, u.address, u.payload, sizeof(u.payload),
                    (uint32_t)u.offset);
      }
    }
  }

  std::cout << "[tester] graph=" << graph_path << " vertices=" << G.num_vertices
            << " directed_edges=" << edge_slots << "\n";
  std::cout << "[tester] launcher_runs=" << launcher_runs
            << " adder_runs=" << adder_runs
            << " memReader_runs=" << memreader_runs
            << " closures=" << closures_used << "\n";

  if (watchdog)
  {
    std::cout << "[tester] WATCHDOG: kernels still running after " << steps
              << " dispatches\n";
    return 1;
  }

  // ---- Compare ------------------------------------------------------------
  int mismatches = 0;
  uint64_t kernel_total = 0;
  for (uint32_t v = 0; v < G.num_vertices; v++)
  {
    const uint64_t word = hbm.ptr<uint64_t>(result_base)[v];
    const uint32_t done = (uint32_t)(word >> 32);
    const uint32_t count = (uint32_t)word;
    kernel_total += count;
    if (!done)
    {
      if (mismatches < 20)
        std::cout << "[tester] MISMATCH v=" << v << " never retired\n";
      mismatches++;
    }
    else if (count != ref_per_vertex[v])
    {
      if (mismatches < 20)
        std::cout << "[tester] MISMATCH v=" << v << " kernel=" << count
                  << " ref=" << ref_per_vertex[v] << "\n";
      mismatches++;
    }
  }

  std::cout << "[tester] kernel_total=" << kernel_total
            << " ref_total=" << ref_total
            << " triangles=" << (ref_total / 6) << "\n";

#ifdef USE_GBBS
  {
    // Flatten to a plain CSR; GBBS lives in its own translation unit.
    std::vector<uint32_t> offsets(G.num_vertices + 1, 0);
    for (uint32_t v = 0; v < G.num_vertices; v++)
      offsets[v + 1] = offsets[v] + (uint32_t)G.adj[v].size();
    std::vector<uint32_t> flat;
    flat.reserve(offsets[G.num_vertices]);
    for (uint32_t v = 0; v < G.num_vertices; v++)
      flat.insert(flat.end(), G.adj[v].begin(), G.adj[v].end());

    const size_t gbbs_triangles =
        gbbsTriangleCount(G.num_vertices, offsets.data(), flat.data());
    std::cout << "[tester] gbbs_triangles=" << gbbs_triangles << "\n";
    if (ref_total % 6 != 0 || ref_total / 6 != gbbs_triangles)
    {
      std::cout << "[tester] FAIL: wedge closures " << ref_total
                << " are not six times GBBS's " << gbbs_triangles << "\n";
      return 1;
    }
    std::cout << "[tester] GBBS agrees: every triangle counted six times.\n";
  }
#endif

  if (mismatches == 0 && kernel_total == ref_total)
  {
    std::cout << "[tester] PASS: per-vertex counts match the golden.\n";
    return 0;
  }
  std::cout << "[tester] FAIL: " << mismatches << " mismatching vertices"
            << " (kernel_total=" << kernel_total << " ref_total=" << ref_total
            << ")\n";
  return 1;
}
