#include "../util.h"

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

void ApproxDenseSub(void *mem_0, void *mem_1, void *mem_2, void *mem_3,
                    void *mem_4,
                    hls::stream<vertex_subset_helper_args> &taskOutGlobal,
                    hls::stream<ApproxDenseSub_args> &taskIn);

void vertex_subset_helper(void *mem_0, void *mem_1, void *mem_2,
                          void *mem_3, void *mem_4, void *mem_5,
                          hls::stream<vertex_subset_helper_args> &taskIn,
                          hls::stream<uint64_t> &argOut,
                          hls::stream<lock_req> &toLock0,
                          hls::stream<lock_resp> &fromLock0,
                          hls::stream<lock_req> &toLock1,
                          hls::stream<lock_resp> &fromLock1);

static const uint8_t LOCK_OP_STOP = 0xF;

struct Edge
{
  uint32_t src = 0;
  uint32_t dst = 0;
};

struct Graph
{
  uint32_t num_vertices = 0;
  uint32_t num_edges = 0;
  std::vector<uint32_t> offsets;
  std::vector<uint32_t> neighbors;

  uint32_t degree(uint32_t u) const { return offsets[u + 1] - offsets[u]; }
};

static bool parseEdge(std::string line, Edge &edge)
{
  const size_t comment = line.find('#');
  if (comment != std::string::npos)
    line.resize(comment);
  std::replace(line.begin(), line.end(), ',', ' ');
  std::replace(line.begin(), line.end(), '\t', ' ');

  uint32_t src = 0, dst = 0;
  std::istringstream iss(line);
  if (!(iss >> src >> dst))
    return false;

  edge.src = src;
  edge.dst = dst;
  return true;
}

static bool loadUndirected(const std::string &path, Graph &G)
{
  std::ifstream f(path);
  if (!f.is_open())
  {
    std::cerr << "[tester] cannot open graph: " << path << "\n";
    return false;
  }

  std::vector<Edge> input_edges;
  uint32_t max_vertex = 0;
  std::string line;
  while (std::getline(f, line))
  {
    Edge e;
    if (!parseEdge(line, e))
      continue;
    max_vertex = std::max(max_vertex, std::max(e.src, e.dst));
    input_edges.push_back(e);
  }

  G.num_vertices = max_vertex + 1;
  G.num_edges = (uint32_t)input_edges.size();
  std::vector<uint32_t> degree(G.num_vertices, 0);
  for (const Edge &e : input_edges)
  {
    degree[e.src]++;
    if (e.dst != e.src)
      degree[e.dst]++;
  }

  G.offsets.assign((size_t)G.num_vertices + 1, 0);
  for (uint32_t v = 0; v < G.num_vertices; v++)
    G.offsets[(size_t)v + 1] = G.offsets[v] + degree[v];

  G.neighbors.assign(G.offsets.back(), 0);
  std::vector<uint32_t> cursor = G.offsets;
  for (const Edge &e : input_edges)
  {
    G.neighbors[cursor[e.src]++] = e.dst;
    if (e.dst != e.src)
      G.neighbors[cursor[e.dst]++] = e.src;
  }

  return true;
}

static density_t densityFromDegreeSum(uint64_t degree_sum, uint32_t vertices)
{
  if (vertices == 0)
    return 0;
  return ((density_t)degree_sum) / ((density_t)vertices * 2);
}

static density_t thresholdFor(density_t density, epsilon_t epsilon)
{
  return 2 * ((density_t)1 + (density_t)epsilon) * density;
}

static epsilon_t parseEpsilon(const char *text)
{
  double value = std::atof(text);
  if (value < 0.0)
    value = 0.0;
  return (epsilon_t)value;
}

static void referenceApproxDenseSub(const Graph &G, epsilon_t epsilon,
                                    std::vector<uint32_t> &best,
                                    density_t &best_density)
{
  const uint32_t n = G.num_vertices;
  std::vector<uint32_t> degree(n, 0);
  std::vector<uint8_t> active(n, 1);
  std::vector<uint32_t> current;
  current.reserve(n);

  for (uint32_t v = 0; v < n; v++)
  {
    degree[v] = G.degree(v);
    current.push_back(v);
  }

  best.clear();
  best_density = 0;
  bool have_best = false;
  while (!current.empty())
  {
    uint64_t degree_sum = 0;
    for (uint32_t v : current)
      degree_sum += degree[v];

    density_t density = densityFromDegreeSum(degree_sum, (uint32_t)current.size());
    if (!have_best || density > best_density)
    {
      have_best = true;
      best_density = density;
      best = current;
    }

    density_t threshold = thresholdFor(density, epsilon);
    std::vector<uint32_t> removed;
    std::vector<uint32_t> kept;
    for (uint32_t v : current)
    {
      if ((density_t)degree[v] < threshold)
        removed.push_back(v);
      else
        kept.push_back(v);
    }

    if (removed.empty())
      break;

    for (uint32_t v : removed)
    {
      active[v] = 0;
      degree[v] = 0;
    }

    for (uint32_t v : removed)
    {
      for (uint32_t i = G.offsets[v]; i < G.offsets[v + 1]; i++)
      {
        uint32_t u = G.neighbors[i];
        if (active[u] && degree[u] != 0)
          degree[u]--;
      }
    }

    current.swap(kept);
  }
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
                          uint64_t previous, bool success)
{
  lock_resp resp;
  resp.data = 0;
  resp.data(0, 0) = success ? 1 : 0;
  resp.data(1, 1) = success ? 1 : 0;
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
    uint64_t previous = 0;

    if (op == LOCK_OP_ADD_N_RETURN_CURRENT)
    {
      if (mode == ATOMIC_MODE_WORD)
      {
        uint32_t *p = reinterpret_cast<uint32_t *>(mem + addr);
        previous = *p;
        *p = (uint32_t)((int32_t)*p + (int32_t)(uint32_t)value);
      }
      else
      {
        uint64_t *p = reinterpret_cast<uint64_t *>(mem + addr);
        previous = *p;
        *p = previous + value;
      }
    }
    else if (op == LOCK_OP_SET_AND_RETURN_CURRENT)
    {
      if (mode == ATOMIC_MODE_WORD)
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
    }

    writeLockResp(fromLock, addr, previous, true);
  }
}

int main(int argc, char **argv)
{
  if (argc < 2)
  {
    std::cerr << "usage: " << argv[0]
              << " <graph.txt> [epsilon] [max_rounds]\n";
    return 1;
  }

  const std::string graph_file = argv[1];
  const epsilon_t epsilon = (argc > 2) ? parseEpsilon(argv[2]) : parseEpsilon("0.1");
  const uint64_t max_rounds_arg =
      (argc > 3) ? std::strtoull(argv[3], 0, 0) : 0;

  Graph G;
  if (!loadUndirected(graph_file, G))
    return 1;

  const uint32_t n = G.num_vertices;
  const uint64_t default_max_rounds = std::max<uint64_t>(10000, 8ull * n + 128);
  const uint64_t max_rounds =
      (max_rounds_arg != 0) ? max_rounds_arg : default_max_rounds;

  std::vector<uint32_t> best_ref;
  density_t best_density_ref = 0;
  referenceApproxDenseSub(G, epsilon, best_ref, best_density_ref);

  std::cout << "[tester] graph=" << graph_file << " vertices=" << n
            << " edges=" << G.num_edges << " epsilon=" << epsilon.to_double()
            << " max_rounds=" << max_rounds << std::endl;

  uint64_t cap = (uint64_t)G.neighbors.size() * sizeof(uint32_t) +
                 2ull * n * sizeof(uint64_t) +
                 4ull * n * sizeof(uint32_t) + sizeof(uint64_t) +
                 sizeof(ApproxDenseSub_args);
  cap = cap * 2 + (1u << 20);
  Hbm hbm(cap);

  Addr neighbors_base = hbm.alloc(std::max<uint64_t>(
      (uint64_t)G.neighbors.size() * sizeof(uint32_t), sizeof(uint32_t)));
  for (size_t i = 0; i < G.neighbors.size(); i++)
    hbm.ptr<uint32_t>(neighbors_base)[i] = G.neighbors[i];

  Addr graph_base = hbm.alloc(2ull * n * sizeof(uint64_t));
  for (uint32_t u = 0; u < n; u++)
  {
    uint64_t neighbor_addr =
        neighbors_base + (uint64_t)G.offsets[u] * sizeof(uint32_t);
    hbm.ptr<uint64_t>(graph_base)[2 * u + 0] = neighbor_addr;
    hbm.ptr<uint64_t>(graph_base)[2 * u + 1] = (uint64_t)G.degree(u);
  }

  Addr degree_base = hbm.alloc((uint64_t)n * sizeof(uint32_t));
  Addr frontier0_base = hbm.alloc((uint64_t)n * sizeof(uint32_t));
  Addr frontier1_base = hbm.alloc((uint64_t)n * sizeof(uint32_t));
  Addr frontier2_base = hbm.alloc((uint64_t)n * sizeof(uint32_t));
  Addr nextFChar_base = hbm.alloc(sizeof(uint64_t));
  Addr cont_base = hbm.alloc(sizeof(ApproxDenseSub_args));

  for (uint32_t v = 0; v < n; v++)
  {
    hbm.ptr<uint32_t>(degree_base)[v] = G.degree(v);
    hbm.ptr<uint32_t>(frontier0_base)[v] = v;
  }

  ApproxDenseSub_args root{};
  root.counter = 0;
  root.vertex_count = n;
  root.round = 0;
  root.epsilon = epsilon;
  root.frontier_length = n;
  root.active = 0;
  root.done = 0;
  root.graph = graph_base;
  root.degree = degree_base;
  root.frontier0 = frontier0_base;
  root.frontier1 = frontier1_base;
  root.frontier2 = frontier2_base;
  root.nextFChar = nextFChar_base;
  root.cont = cont_base;
  root.best_frontier = 0;
  root.best_length = 0;
  root.best_density = 0;
  std::memcpy(hbm.ptr<uint8_t>(cont_base), &root, sizeof(root));

  hls::stream<ApproxDenseSub_args> ads_in("ads_in");
  hls::stream<vertex_subset_helper_args> helper_tasks("helper_tasks");
  hls::stream<vertex_subset_helper_args> helper_in("helper_in");
  hls::stream<uint64_t> argOut("argOut");
  hls::stream<lock_req> toLock0("toLock0");
  hls::stream<lock_resp> fromLock0("fromLock0");
  hls::stream<lock_req> toLock1("toLock1");
  hls::stream<lock_resp> fromLock1("fromLock1");

  std::thread lockThread0(lockServer, &toLock0, &fromLock0, hbm.base());
  std::thread lockThread1(lockServer, &toLock1, &fromLock1, hbm.base());

  ads_in.write(root);
  uint64_t rounds = 0, helpers_run = 0;
  bool watchdog = false;
  while (!ads_in.empty())
  {
    if (rounds >= max_rounds)
    {
      watchdog = true;
      break;
    }

    ApproxDenseSub(hbm.base(), hbm.base(), hbm.base(), hbm.base(), hbm.base(),
                   helper_tasks, ads_in);
    rounds++;

    while (!helper_tasks.empty())
    {
      helper_in.write(helper_tasks.read());
      vertex_subset_helper(hbm.base(), hbm.base(), hbm.base(), hbm.base(),
                           hbm.base(), hbm.base(), helper_in, argOut,
                           toLock0, fromLock0, toLock1, fromLock1);
      helpers_run++;

      uint64_t cont_addr = argOut.read();
      (*hbm.ptr<uint32_t>(cont_addr))--;
    }

    const ApproxDenseSub_args *cont = hbm.ptr<ApproxDenseSub_args>(cont_base);
    if (cont->done)
      break;
    if (cont->counter == 0)
    {
      ApproxDenseSub_args next;
      std::memcpy(&next, cont, sizeof(next));
      ads_in.write(next);
    }
  }

  toLock0.write(make_lock_req(0, 0, (LockOperation)LOCK_OP_STOP, false,
                              ATOMIC_MODE_DOUBLEWORD));
  toLock1.write(make_lock_req(0, 0, (LockOperation)LOCK_OP_STOP, false,
                              ATOMIC_MODE_DOUBLEWORD));
  lockThread0.join();
  lockThread1.join();

  const ApproxDenseSub_args *cont = hbm.ptr<ApproxDenseSub_args>(cont_base);
  std::vector<uint32_t> best_kernel(cont->best_length);
  for (uint32_t i = 0; i < cont->best_length; i++)
    best_kernel[i] = hbm.ptr<uint32_t>(cont->best_frontier)[i];

  std::vector<uint32_t> sorted_ref = best_ref;
  std::vector<uint32_t> sorted_kernel = best_kernel;
  std::sort(sorted_ref.begin(), sorted_ref.end());
  std::sort(sorted_kernel.begin(), sorted_kernel.end());

  std::cout << "[tester] ApproxDenseSub rounds=" << rounds
            << " helpers_run=" << helpers_run
            << " best_length=" << cont->best_length
            << " best_density=" << std::setprecision(6)
            << cont->best_density.to_double()
            << std::endl;

  if (watchdog)
  {
    std::cout << "[tester] WATCHDOG: kernel did not finish within "
              << max_rounds << " rounds" << std::endl;
  }

  bool density_match = cont->best_density == best_density_ref;
  bool set_match = sorted_kernel == sorted_ref;
  if (!watchdog && density_match && set_match)
  {
    std::cout << "[tester] PASS: kernel Smax matches ApproxDenseSub golden."
              << std::endl;
    return 0;
  }

  std::cout << "[tester] FAIL: expected length=" << best_ref.size()
            << " density=" << best_density_ref.to_double() << std::endl;
  return 1;
}
