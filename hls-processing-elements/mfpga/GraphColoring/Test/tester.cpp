#include "../util.h"

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

void GraphColoring(void *mem_0,
                   hls::stream<color_init_helper_args> &taskOutGlobal,
                   hls::stream<color_loop_helper_args> &taskOutGlobal_1,
                   hls::stream<GraphColoring_args> &taskIn);

void color_init_helper(void *mem_0, void *mem_1, void *mem_2, void *mem_3,
                       void *mem_4, void *mem_5, void *mem_6,
                       hls::stream<color_init_helper_args> &taskIn,
                       hls::stream<uint64_t> &argOut,
                       hls::stream<lock_req> &toLock,
                       hls::stream<lock_resp> &fromLock);

void color_loop_helper(void *mem_0, void *mem_1, void *mem_2, void *mem_3,
                       void *mem_4, void *mem_5, void *mem_6,
                       hls::stream<color_loop_helper_args> &taskIn,
                       hls::stream<uint64_t> &argOut,
                       hls::stream<lock_req> &toLock0,
                       hls::stream<lock_resp> &fromLock0,
                       hls::stream<lock_req> &toLock1,
                       hls::stream<lock_resp> &fromLock1,
                       hls::stream<lock_req> &toLock2,
                       hls::stream<lock_resp> &fromLock2);

static const uint8_t LOCK_OP_STOP = 0xF;
static const uint32_t UNCOLORED = 0xFFFFFFFFu;

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

  std::istringstream iss(line);
  return (iss >> edge.src >> edge.dst) ? true : false;
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
                          bool write_occurred = true)
{
  lock_resp resp;
  resp = 0;
  resp(0, 0) = success ? 1 : 0;
  resp(1, 1) = write_occurred ? 1 : 0;
  resp(71, 8) = tag;
  resp(135, 72) = previous;
  fromLock->write(resp);
}

static void lockServer(hls::stream<lock_req> *toLock,
                       hls::stream<lock_resp> *fromLock, uint8_t *mem)
{
  for (;;)
  {
    lock_req req = toLock->read();
    uint8_t op = (uint8_t)req(131, 128);
    if (op == LOCK_OP_STOP)
      break;

    uint64_t addr = (uint64_t)req(63, 0);
    uint64_t value = (uint64_t)req(127, 64);
    uint8_t mode = (uint8_t)req(134, 133);
    uint64_t previous = 0;
    bool write_occurred = true;

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
    else if (op == LOCK_OP_SET_IF_GREATER_AND_RETURN_CURRENT)
    {
      if (mode == ATOMIC_MODE_WORD)
      {
        uint32_t *p = reinterpret_cast<uint32_t *>(mem + addr);
        previous = *p;
        if ((uint32_t)value > *p)
          *p = (uint32_t)value;
        else
          write_occurred = false;
      }
      else
      {
        uint64_t *p = reinterpret_cast<uint64_t *>(mem + addr);
        previous = *p;
        if (value > *p)
          *p = value;
        else
          write_occurred = false;
      }
    }

    writeLockResp(fromLock, addr, previous, true, write_occurred);
  }
}

static uint32_t floorLog2(uint32_t x)
{
  uint32_t out = 0;
  while (x >>= 1)
    out++;
  return out;
}

static bool runsBefore(uint32_t left_log_degree, uint32_t left_rank,
                       uint32_t right_log_degree, uint32_t right_rank)
{
  return left_log_degree > right_log_degree ||
         (left_log_degree == right_log_degree && left_rank < right_rank);
}

static void referenceColoring(const Graph &G, const std::vector<uint32_t> &rank,
                              uint32_t max_colors,
                              std::vector<uint32_t> &colors,
                              uint32_t &colors_used)
{
  const uint32_t n = G.num_vertices;
  std::vector<uint32_t> priority(n, 0);
  colors.assign(n, UNCOLORED);
  colors_used = 0;

  for (uint32_t v = 0; v < n; v++)
  {
    uint32_t v_log = floorLog2(G.degree(v));
    for (uint32_t i = G.offsets[v]; i < G.offsets[v + 1]; i++)
    {
      uint32_t u = G.neighbors[i];
      if (runsBefore(floorLog2(G.degree(u)), rank[u], v_log, rank[v]))
        priority[v]++;
    }
  }

  uint32_t finished = 0;
  while (finished < n)
  {
    std::vector<uint32_t> roots;
    for (uint32_t v = 0; v < n; v++)
      if (colors[v] == UNCOLORED && priority[v] == 0)
        roots.push_back(v);

    if (roots.empty())
      break;

    for (uint32_t v : roots)
    {
      std::vector<uint8_t> used(max_colors, 0);
      for (uint32_t i = G.offsets[v]; i < G.offsets[v + 1]; i++)
      {
        uint32_t u = G.neighbors[i];
        if (colors[u] != UNCOLORED && colors[u] < max_colors)
          used[colors[u]] = 1;
      }

      uint32_t chosen = 0;
      while (chosen + 1 < max_colors && used[chosen])
        chosen++;
      colors[v] = chosen;
      colors_used = std::max(colors_used, chosen + 1);
    }

    finished += (uint32_t)roots.size();
    for (uint32_t v : roots)
    {
      for (uint32_t i = G.offsets[v]; i < G.offsets[v + 1]; i++)
      {
        uint32_t u = G.neighbors[i];
        if (priority[u] > 0)
          priority[u]--;
      }
    }
  }
}

static bool validateColoring(const Graph &G, const std::vector<uint32_t> &colors,
                             uint32_t max_colors, uint32_t &bad_edges,
                             uint32_t &uncolored)
{
  bad_edges = 0;
  uncolored = 0;
  for (uint32_t v = 0; v < G.num_vertices; v++)
  {
    if (colors[v] == UNCOLORED || colors[v] >= max_colors)
      uncolored++;

    for (uint32_t i = G.offsets[v]; i < G.offsets[v + 1]; i++)
    {
      uint32_t u = G.neighbors[i];
      if (u > v && colors[u] == colors[v])
        bad_edges++;
    }
  }

  return bad_edges == 0 && uncolored == 0;
}

int main(int argc, char **argv)
{
  if (argc < 2)
  {
    std::cerr << "usage: " << argv[0] << " <graph.txt> [max_colors]\n";
    return 1;
  }

  Graph G;
  if (!loadUndirected(argv[1], G))
    return 1;

  const uint32_t n = G.num_vertices;
  const uint32_t max_colors = argc > 2 ? (uint32_t)std::strtoul(argv[2], nullptr, 0) : 64;

  std::vector<uint32_t> rank(n, 0);
  for (uint32_t v = 0; v < n; v++)
    rank[v] = v;

  std::vector<uint32_t> expected_colors;
  uint32_t expected_colors_used = 0;
  referenceColoring(G, rank, max_colors, expected_colors, expected_colors_used);

  uint64_t cap = (uint64_t)G.neighbors.size() * sizeof(uint32_t) +
                 2ull * n * sizeof(uint64_t) +
                 5ull * n * sizeof(uint32_t) +
                 sizeof(GraphColoring_args) + 2ull * sizeof(uint64_t) +
                 (1u << 20);
  Hbm hbm(cap);

  Addr neighbors_base = hbm.alloc(std::max<uint64_t>(
      (uint64_t)G.neighbors.size() * sizeof(uint32_t), sizeof(uint32_t)));
  for (size_t i = 0; i < G.neighbors.size(); i++)
    hbm.ptr<uint32_t>(neighbors_base)[i] = G.neighbors[i];

  Addr graph_base = hbm.alloc(2ull * n * sizeof(uint64_t));
  for (uint32_t v = 0; v < n; v++)
  {
    uint64_t neighbor_addr =
        neighbors_base + (uint64_t)G.offsets[v] * sizeof(uint32_t);
    hbm.ptr<uint64_t>(graph_base)[2 * v + 0] = neighbor_addr;
    hbm.ptr<uint64_t>(graph_base)[2 * v + 1] = G.degree(v);
  }

  Addr rank_base = hbm.alloc((uint64_t)n * sizeof(uint32_t));
  Addr priority_base = hbm.alloc((uint64_t)n * sizeof(uint32_t));
  Addr color_base = hbm.alloc((uint64_t)n * sizeof(uint32_t));
  Addr roots0_base = hbm.alloc((uint64_t)n * sizeof(uint32_t));
  Addr roots1_base = hbm.alloc((uint64_t)n * sizeof(uint32_t));
  Addr nextFChar_base = hbm.alloc(sizeof(uint64_t));
  Addr colorsUsed_base = hbm.alloc(sizeof(uint32_t));
  Addr cont_base = hbm.alloc(sizeof(GraphColoring_args));

  for (uint32_t v = 0; v < n; v++)
    hbm.ptr<uint32_t>(rank_base)[v] = rank[v];

  hls::stream<GraphColoring_args> gcIn("gcIn");
  hls::stream<color_init_helper_args> initTasks("initTasks");
  hls::stream<color_init_helper_args> initIn("initIn");
  hls::stream<color_loop_helper_args> loopTasks("loopTasks");
  hls::stream<color_loop_helper_args> loopIn("loopIn");
  hls::stream<uint64_t> initArgOut("initArgOut");
  hls::stream<uint64_t> loopArgOut("loopArgOut");
  hls::stream<lock_req> toLock0("toLock0");
  hls::stream<lock_resp> fromLock0("fromLock0");
  hls::stream<lock_req> toLock1("toLock1");
  hls::stream<lock_resp> fromLock1("fromLock1");
  hls::stream<lock_req> toLock2("toLock2");
  hls::stream<lock_resp> fromLock2("fromLock2");

  std::thread lockThread0(lockServer, &toLock0, &fromLock0, hbm.base());
  std::thread lockThread1(lockServer, &toLock1, &fromLock1, hbm.base());
  std::thread lockThread2(lockServer, &toLock2, &fromLock2, hbm.base());

  GraphColoring_args root{};
  root.counter = 0;
  root.vertex_count = n;
  root.init_done = 0;
  root.active = 0;
  root.done = 0;
  root.finished = 0;
  root.frontier_length = 0;
  root.max_colors = max_colors;
  root.graph = graph_base;
  root.rank = rank_base;
  root.priority = priority_base;
  root.color = color_base;
  root.roots0 = roots0_base;
  root.roots1 = roots1_base;
  root.nextFChar = nextFChar_base;
  root.colorsUsed = colorsUsed_base;
  root.cont = cont_base;
  std::memcpy(hbm.ptr<uint8_t>(cont_base), &root, sizeof(root));

  gcIn.write(root);
  uint32_t launcher_calls = 0, init_run = 0, helpers_run = 0;
  bool watchdog = false;
  while (!gcIn.empty())
  {
    if (launcher_calls >= n + 4)
    {
      watchdog = true;
      break;
    }

    GraphColoring(hbm.base(), initTasks, loopTasks, gcIn);
    launcher_calls++;

    while (!initTasks.empty())
    {
      initIn.write(initTasks.read());
      color_init_helper(hbm.base(), hbm.base(), hbm.base(), hbm.base(),
                        hbm.base(), hbm.base(), hbm.base(),
                        initIn, initArgOut, toLock0, fromLock0);
      init_run++;

      uint64_t cont = initArgOut.read();
      (*hbm.ptr<uint32_t>(cont))--;
    }

    while (!loopTasks.empty())
    {
      loopIn.write(loopTasks.read());
      color_loop_helper(hbm.base(), hbm.base(), hbm.base(), hbm.base(),
                        hbm.base(), hbm.base(), hbm.base(),
                        loopIn, loopArgOut, toLock0, fromLock0,
                        toLock1, fromLock1, toLock2, fromLock2);
      helpers_run++;

      uint64_t cont = loopArgOut.read();
      (*hbm.ptr<uint32_t>(cont))--;
    }

    const GraphColoring_args *cont = hbm.ptr<GraphColoring_args>(cont_base);
    if (cont->done)
      break;
    if (cont->counter == 0)
    {
      GraphColoring_args next;
      std::memcpy(&next, cont, sizeof(next));
      gcIn.write(next);
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

  std::vector<uint32_t> got(n, UNCOLORED);
  uint32_t highest_plus_one = 0;
  for (uint32_t v = 0; v < n; v++)
  {
    got[v] = hbm.ptr<uint32_t>(color_base)[v];
    if (got[v] != UNCOLORED)
      highest_plus_one = std::max(highest_plus_one, got[v] + 1);
  }

  uint32_t bad_edges = 0, uncolored = 0;
  bool valid = validateColoring(G, got, max_colors, bad_edges, uncolored);
  const GraphColoring_args *gc_cont = hbm.ptr<GraphColoring_args>(cont_base);
  uint32_t colors_used = *hbm.ptr<uint32_t>(colorsUsed_base);

  std::cout << "[tester] GraphColoring launcher_calls=" << launcher_calls
            << " init_run=" << init_run
            << " helpers_run=" << helpers_run
            << " finished=" << gc_cont->finished
            << " colors_used=" << colors_used
            << " expected_colors_used=" << expected_colors_used << "\n";

  if (!watchdog && valid && gc_cont->finished == n &&
      colors_used == highest_plus_one)
  {
    std::cout << "[tester] PASS: coloring is valid.\n";
    return 0;
  }

  std::cout << "[tester] FAIL: bad_edges=" << bad_edges
            << " uncolored=" << uncolored
            << " watchdog=" << watchdog
            << " highest_plus_one=" << highest_plus_one << "\n";
  return 1;
}
