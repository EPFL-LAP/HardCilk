// Functional-accuracy CPU test for the HardCilk Bellman-Ford kernels.
//
// This links the real synthesizable code from ../BellmanFord.cpp and drives it
// from a plain CPU harness. The CPU golden is a directed weighted Bellman-Ford:
// unreachable vertices remain +INF, and every vertex reachable from an
// accessible negative cycle is marked -INF.

#include "../util.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <queue>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

void BellmanFord(void *mem_0,
                 hls::stream<sparse_edgemap_helper_args> &taskOutGlobal,
                 hls::stream<BFS_args> &taskIn);

void sparse_edgemap_helper(
    void *mem_0, void *mem_1, void *mem_2, void *mem_3, void *mem_4,
    void *mem_5, void *mem_6, hls::stream<sparse_edgemap_helper_args> &taskIn,
    hls::stream<uint64_t> &argOut, hls::stream<lock_req> &toLock0,
    hls::stream<lock_resp> &fromLock0, hls::stream<lock_req> &toLock1,
    hls::stream<lock_resp> &fromLock1, hls::stream<lock_req> &toLock2,
    hls::stream<lock_resp> &fromLock2);

static const uint8_t LOCK_OP_STOP = 0xF;

struct Edge
{
  uint32_t src = 0;
  uint32_t dst = 0;
  float weight = 0.0f;
};

struct Graph
{
  uint32_t num_vertices = 0;
  uint32_t num_edges = 0;
  std::vector<uint32_t> forward_offsets;
  std::vector<Edge> edges;

  uint32_t degree(uint32_t u) const
  {
    return forward_offsets[u + 1] - forward_offsets[u];
  }
};

static uint32_t floatToBits(float value)
{
  uint32_t bits = 0;
  std::memcpy(&bits, &value, sizeof(bits));
  return bits;
}

static float bitsToFloat(uint32_t bits)
{
  float value = 0.0f;
  std::memcpy(&value, &bits, sizeof(value));
  return value;
}

static bool parseWeightedEdge(std::string line, Edge &edge)
{
  const size_t comment = line.find('#');
  if (comment != std::string::npos)
    line.resize(comment);
  std::replace(line.begin(), line.end(), ',', ' ');
  std::replace(line.begin(), line.end(), '\t', ' ');

  uint32_t src = 0, dst = 0;
  double weight = 0.0;
  std::istringstream iss(line);
  if (!(iss >> src >> dst >> weight))
    return false;

  edge.src = src;
  edge.dst = dst;
  edge.weight = (float)weight;
  return true;
}

static bool loadWeightedDirected(const std::string &path, uint32_t source,
                                 Graph &G)
{
  std::ifstream f(path);
  if (!f.is_open())
  {
    std::cerr << "[tester] cannot open graph: " << path << "\n";
    return false;
  }

  std::vector<Edge> input_edges;
  std::vector<uint32_t> degree;
  uint32_t max_vertex = source;
  std::string line;
  while (std::getline(f, line))
  {
    Edge e;
    if (!parseWeightedEdge(line, e))
      continue;

    max_vertex = std::max(max_vertex, std::max(e.src, e.dst));
    if (e.src >= degree.size())
      degree.resize((size_t)e.src + 1, 0);
    degree[e.src]++;
    input_edges.push_back(e);
  }

  G.num_vertices = max_vertex + 1;
  G.num_edges = (uint32_t)input_edges.size();
  G.forward_offsets.assign((size_t)G.num_vertices + 1, 0);
  for (uint32_t v = 0; v < G.num_vertices; v++)
  {
    uint32_t d = (v < degree.size()) ? degree[v] : 0;
    G.forward_offsets[(size_t)v + 1] = G.forward_offsets[v] + d;
  }

  G.edges.assign(G.num_edges, Edge{});
  std::vector<uint32_t> cursor = G.forward_offsets;
  for (size_t i = 0; i < input_edges.size(); i++)
  {
    const Edge &e = input_edges[i];
    G.edges[cursor[e.src]++] = e;
  }

  return true;
}

static void referenceBellmanFord(const Graph &G, uint32_t source,
                                 std::vector<double> &dist,
                                 std::vector<uint8_t> &negative_infinite)
{
  const double INF = std::numeric_limits<double>::infinity();
  const double EPS = 1e-12;
  const uint32_t n = G.num_vertices;

  dist.assign(n, INF);
  negative_infinite.assign(n, 0);
  if (source >= n)
    return;

  dist[source] = 0.0;
  for (uint32_t pass = 0; pass + 1 < n; pass++)
  {
    bool changed = false;
    for (size_t i = 0; i < G.edges.size(); i++)
    {
      const Edge &e = G.edges[i];
      if (std::isinf(dist[e.src]))
        continue;
      const double candidate = dist[e.src] + (double)e.weight;
      if (candidate + EPS < dist[e.dst])
      {
        dist[e.dst] = candidate;
        changed = true;
      }
    }
    if (!changed)
      break;
  }

  std::queue<uint32_t> q;
  for (size_t i = 0; i < G.edges.size(); i++)
  {
    const Edge &e = G.edges[i];
    if (std::isinf(dist[e.src]))
      continue;
    const double candidate = dist[e.src] + (double)e.weight;
    if (candidate + EPS < dist[e.dst] && !negative_infinite[e.dst])
    {
      negative_infinite[e.dst] = 1;
      q.push(e.dst);
    }
  }

  while (!q.empty())
  {
    uint32_t u = q.front();
    q.pop();
    for (uint32_t j = G.forward_offsets[u]; j < G.forward_offsets[u + 1]; j++)
    {
      uint32_t v = G.edges[j].dst;
      if (!negative_infinite[v])
      {
        negative_infinite[v] = 1;
        q.push(v);
      }
    }
  }

  for (uint32_t v = 0; v < n; v++)
    if (negative_infinite[v])
      dist[v] = -INF;
}

struct Hbm
{
  std::vector<uint8_t> mem;
  uint64_t off = 512;

  explicit Hbm(uint64_t cap) : mem(cap, 0) {}
  uint8_t *base() { return mem.data(); }

  uint64_t alloc(uint64_t bytes, uint64_t align = 512)
  {
    off = (off + align - 1) / align * align;
    uint64_t addr = off;
    off += bytes;
    if (off > mem.size())
    {
      std::cerr << "[tester] HBM model out of space\n";
      std::abort();
    }
    return addr;
  }

  template <typename T>
  T *ptr(uint64_t addr)
  {
    return reinterpret_cast<T *>(base() + addr);
  }
};

static void writeLockResp(hls::stream<lock_resp> *fromLock, uint64_t tag,
                          uint64_t previous, bool success,
                          bool write_occurred)
{
  lock_resp resp;
  resp.data = 0;
  resp.data(0, 0) = success ? 1 : 0;
  resp.data(1, 1) = write_occurred ? 1 : 0;
  resp.data(71, 8) = tag;
  resp.data(135, 72) = previous;
  resp.keep = -1;
  resp.strb = -1;
  resp.last = 1;
  fromLock->write(resp);
}

static void lockServer(hls::stream<lock_req> *toLock,
                       hls::stream<lock_resp> *fromLock, uint8_t *mem)
{
  for (;;)
  {
    lock_req req = toLock->read();
    uint8_t op = (uint8_t)req.data(131, 128);
    if (op == LOCK_OP_STOP)
      break;

    uint64_t addr = (uint64_t)req.data(63, 0);
    uint64_t value = (uint64_t)req.data(127, 64);
    uint8_t mode = (uint8_t)req.data(134, 133);
    bool float_compare = req.data(135, 135) != 0;
    uint64_t previous = 0;
    bool write_occurred = false;

    if (op == LOCK_OP_SET_IF_LESS_AND_RETURN_CURRENT)
    {
      if (mode == ATOMIC_MODE_WORD)
      {
        uint32_t *p = reinterpret_cast<uint32_t *>(mem + addr);
        uint32_t old_bits = *p;
        uint32_t new_bits = (uint32_t)value;
        previous = old_bits;
        bool should_write = false;
        if (float_compare)
        {
          should_write = bitsToFloat(new_bits) < bitsToFloat(old_bits);
        }
        else
        {
          should_write = new_bits < old_bits;
        }
        if (should_write)
        {
          *p = new_bits;
          write_occurred = true;
        }
      }
    }
    else if (op == LOCK_OP_SET_AND_RETURN_CURRENT)
    {
      if (mode == ATOMIC_MODE_BYTE)
      {
        uint8_t *p = mem + addr;
        previous = *p;
        *p = (uint8_t)value;
      }
      else if (mode == ATOMIC_MODE_WORD)
      {
        uint32_t *p = reinterpret_cast<uint32_t *>(mem + addr);
        previous = *p;
        *p = (uint32_t)value;
      }
      else
      {
        uint64_t *p = reinterpret_cast<uint64_t *>(mem + addr);
        previous = *p;
        *p = value;
      }
      write_occurred = true;
    }
    else if (op == LOCK_OP_ADD_N_RETURN_CURRENT)
    {
      uint64_t *p = reinterpret_cast<uint64_t *>(mem + addr);
      previous = *p;
      *p = previous + value;
      write_occurred = true;
    }
    else
    {
      uint64_t *p = reinterpret_cast<uint64_t *>(mem + addr);
      previous = *p;
    }

    writeLockResp(fromLock, addr, previous, true, write_occurred);
  }
}

static bool distancesMatch(double ref, float got)
{
  if (std::isinf(ref))
    return std::isinf(got) && ((ref < 0.0) == (got < 0.0));
  if (!std::isfinite(got))
    return false;

  const double diff = std::fabs((double)got - ref);
  const double scale = std::max(1.0, std::fabs(ref));
  return diff <= 1e-3 * scale;
}

int main(int argc, char **argv)
{
  if (argc < 2)
  {
    std::cerr << "usage: " << argv[0]
              << " <weighted-graph.csv> [source] [max_rounds]\n";
    return 1;
  }

  const std::string graph_file = argv[1];
  const uint32_t source = (argc > 2) ? (uint32_t)std::strtoul(argv[2], 0, 0) : 0;
  const uint64_t max_rounds_arg =
      (argc > 3) ? std::strtoull(argv[3], 0, 0) : 0;

  Graph G;
  if (!loadWeightedDirected(graph_file, source, G))
    return 1;
  if (G.num_vertices == 0)
  {
    std::cerr << "[tester] empty graph\n";
    return 1;
  }

  std::vector<double> dist_ref;
  std::vector<uint8_t> neg_inf;
  referenceBellmanFord(G, source, dist_ref, neg_inf);
  bool has_negative_infinite = false;
  for (size_t i = 0; i < neg_inf.size(); i++)
    has_negative_infinite = has_negative_infinite || neg_inf[i] != 0;

  const uint32_t n = G.num_vertices;
  const uint64_t default_max_rounds =
      std::max<uint64_t>(10000, (has_negative_infinite ? 2ull : 8ull) * n + 128);
  const uint64_t max_rounds =
      (max_rounds_arg != 0) ? max_rounds_arg : default_max_rounds;

  std::cout << "[tester] graph=" << graph_file << " vertices=" << n
            << " edges=" << G.num_edges << " source=" << source
            << " max_rounds=" << max_rounds << std::endl;

  const uint64_t edgeBytes =
      std::max<uint64_t>((uint64_t)G.edges.size() * sizeof(uint64_t),
                         sizeof(uint64_t));
  uint64_t cap = edgeBytes + 2ull * n * sizeof(uint64_t) +
                 2ull * n * sizeof(float) + (uint64_t)n +
                 2ull * n * sizeof(uint32_t) + sizeof(uint64_t) +
                 sizeof(BFS_args);
  cap = cap * 2 + (1u << 20);
  Hbm hbm(cap);

  Addr edges_base = hbm.alloc(edgeBytes);
  for (size_t i = 0; i < G.edges.size(); i++)
  {
    uint64_t packed = ((uint64_t)floatToBits(G.edges[i].weight) << 32) |
                      (uint64_t)G.edges[i].dst;
    hbm.ptr<uint64_t>(edges_base)[i] = packed;
  }

  Addr graph_base = hbm.alloc(2ull * n * sizeof(uint64_t));
  for (uint32_t u = 0; u < n; u++)
  {
    uint64_t edge_addr = edges_base + (uint64_t)G.forward_offsets[u] * sizeof(uint64_t);
    hbm.ptr<uint64_t>(graph_base)[2 * u + 0] = edge_addr >> 1;
    hbm.ptr<uint64_t>(graph_base)[2 * u + 1] = (uint64_t)G.degree(u);
  }

  Addr distance_base = hbm.alloc((uint64_t)n * sizeof(float));
  // relaxed[] holds a 4-byte per-vertex round stamp (last round enqueued),
  // advanced atomically by SET_IF_GREATER -- not a 1-byte flag.
  Addr relaxed_base = hbm.alloc((uint64_t)n * sizeof(uint32_t));
  Addr frontier0_base = hbm.alloc((uint64_t)n * sizeof(uint32_t));
  Addr frontier1_base = hbm.alloc((uint64_t)n * sizeof(uint32_t));
  Addr nextFChar_base = hbm.alloc(sizeof(uint64_t));
  Addr cont_base = hbm.alloc(sizeof(BFS_args));

  for (uint32_t v = 0; v < n; v++)
    hbm.ptr<float>(distance_base)[v] = std::numeric_limits<float>::infinity();

  BFS_args root{};
  root.counter = 0;
  root.source = source;
  root.vertex_count = n;
  root.round = 0;
  root.frontier_length = 0;
  root.active = 0;
  root.done = 0;
  root.graph = graph_base;
  root.distance = distance_base;
  root.relaxed = relaxed_base;
  root.frontier0 = frontier0_base;
  root.frontier1 = frontier1_base;
  root.nextFChar = nextFChar_base;
  root.cont = cont_base;
  std::memcpy(hbm.ptr<uint8_t>(cont_base), &root, sizeof(root));

  hls::stream<BFS_args> bf_in("bf_in");
  hls::stream<sparse_edgemap_helper_args> helper_tasks("helper_tasks");
  hls::stream<sparse_edgemap_helper_args> helper_in("helper_in");
  hls::stream<uint64_t> argOut("argOut");
  hls::stream<lock_req> toLock0("toLock0");
  hls::stream<lock_resp> fromLock0("fromLock0");
  hls::stream<lock_req> toLock1("toLock1");
  hls::stream<lock_resp> fromLock1("fromLock1");
  hls::stream<lock_req> toLock2("toLock2");
  hls::stream<lock_resp> fromLock2("fromLock2");

  std::thread lockThread0(lockServer, &toLock0, &fromLock0, hbm.base());
  std::thread lockThread1(lockServer, &toLock1, &fromLock1, hbm.base());
  std::thread lockThread2(lockServer, &toLock2, &fromLock2, hbm.base());

  bf_in.write(root);
  uint64_t rounds = 0, helpers_run = 0;
  bool watchdog = false;
  while (!bf_in.empty())
  {
    if (rounds >= max_rounds)
    {
      watchdog = true;
      break;
    }

    BellmanFord(hbm.base(), helper_tasks, bf_in);
    rounds++;

    while (!helper_tasks.empty())
    {
      helper_in.write(helper_tasks.read());
      sparse_edgemap_helper(hbm.base(), hbm.base(), hbm.base(), hbm.base(),
                            hbm.base(), hbm.base(), hbm.base(), helper_in,
                            argOut, toLock0, fromLock0, toLock1, fromLock1,
                            toLock2, fromLock2);
      helpers_run++;

      uint64_t cont_addr = argOut.read();
      (*hbm.ptr<uint32_t>(cont_addr))--;
    }

    const BFS_args *cont = hbm.ptr<BFS_args>(cont_base);
    if (cont->done)
      break;
    if (cont->counter == 0)
    {
      BFS_args next;
      std::memcpy(&next, cont, sizeof(next));
      bf_in.write(next);
    }
  }

  toLock0.write(make_lock_req(0, 0, (LockOperation)LOCK_OP_STOP, false,
                              ATOMIC_MODE_DOUBLEWORD));
  toLock1.write(make_lock_req(0, 0, (LockOperation)LOCK_OP_STOP, false,
                              ATOMIC_MODE_DOUBLEWORD));
  toLock2.write(make_lock_req(0, 0, (LockOperation)LOCK_OP_STOP, false,
                              ATOMIC_MODE_DOUBLEWORD));
  lockThread0.join();
  lockThread1.join();
  lockThread2.join();

  std::cout << "[tester] BellmanFord rounds=" << rounds
            << " helpers_run=" << helpers_run << std::endl;

  if (watchdog)
  {
    std::cout << "[tester] WATCHDOG: kernel did not finish within "
              << max_rounds << " rounds";
    if (has_negative_infinite)
      std::cout << " (CPU golden found reachable negative-cycle vertices)";
    std::cout << std::endl;
  }

  std::vector<float> dist_kernel(n);
  for (uint32_t v = 0; v < n; v++)
    dist_kernel[v] = hbm.ptr<float>(distance_base)[v];

  int mismatches = 0;
  for (uint32_t v = 0; v < n; v++)
  {
    if (!distancesMatch(dist_ref[v], dist_kernel[v]))
    {
      if (mismatches < 20)
      {
        std::cout << std::setprecision(8)
                  << "[tester] MISMATCH v=" << v
                  << " kernel=" << dist_kernel[v]
                  << " ref=" << dist_ref[v] << std::endl;
      }
      mismatches++;
    }
  }

  uint32_t reached = 0, neg = 0, unreachable = 0;
  for (uint32_t v = 0; v < n; v++)
  {
    if (std::isinf(dist_ref[v]) && dist_ref[v] > 0.0)
      unreachable++;
    else if (std::isinf(dist_ref[v]) && dist_ref[v] < 0.0)
      neg++;
    else
      reached++;
  }
  std::cout << "[tester] reference finite=" << reached
            << " neg_inf=" << neg << " unreachable=" << unreachable
            << std::endl;

  if (!watchdog && mismatches == 0)
  {
    std::cout << "[tester] PASS: kernel distances match Bellman-Ford golden."
              << std::endl;
    return 0;
  }

  std::cout << "[tester] FAIL: " << mismatches
            << " mismatching vertices." << std::endl;
  return 1;
}
