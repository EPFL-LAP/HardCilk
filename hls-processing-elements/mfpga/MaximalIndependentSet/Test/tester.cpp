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

void NGS(void *mem_0, void *mem_1, void *mem_2, void *mem_3,
         hls::stream<NGS_args> &taskIn,
         hls::stream<uint64_t> &argOut);

void MaximalIndependentSet(void *mem_0,
                           hls::stream<NGS_args> &taskOutGlobal,
                           hls::stream<mis_loop_helper_args> &taskOutGlobal_1,
                           hls::stream<MIS_args> &taskIn);

void mis_loop_helper(void *mem_0, void *mem_1, void *mem_2, void *mem_3,
                     void *mem_4, void *mem_5, void *mem_6,
                     hls::stream<mis_loop_helper_args> &taskIn,
                     hls::stream<uint64_t> &argOut,
                     hls::stream<lock_req> &toLock0,
                     hls::stream<lock_resp> &fromLock0,
                     hls::stream<lock_req> &toLock1,
                     hls::stream<lock_resp> &fromLock1,
                     hls::stream<lock_req> &toLock2,
                     hls::stream<lock_resp> &fromLock2);

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
                          uint64_t previous, bool success)
{
  lock_resp resp;
  resp = 0;
  resp(0, 0) = success ? 1 : 0;
  resp(1, 1) = success ? 1 : 0;
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

    if (op == LOCK_OP_SET_AND_RETURN_CURRENT)
    {
      if (mode == ATOMIC_MODE_BYTE)
      {
        uint8_t *p = mem + addr;
        previous = *p;
        *p = (uint8_t)value;
      }
      else
      {
        uint64_t *p = reinterpret_cast<uint64_t *>(mem + addr);
        previous = *p;
        *p = value;
      }
    }
    else if (op == LOCK_OP_ADD_N_RETURN_CURRENT)
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

    writeLockResp(fromLock, addr, previous, true);
  }
}

static void referenceMIS(const Graph &G, const std::vector<uint32_t> &priority,
                         std::vector<uint8_t> &in_mis)
{
  const uint32_t n = G.num_vertices;
  std::vector<uint32_t> count(n, 0);
  std::vector<uint8_t> covered(n, 0);
  in_mis.assign(n, 0);

  for (uint32_t v = 0; v < n; v++)
  {
    for (uint32_t i = G.offsets[v]; i < G.offsets[v + 1]; i++)
    {
      uint32_t u = G.neighbors[i];
      if (priority[u] > priority[v])
        count[v]++;
    }
  }

  uint32_t num_finished = 0;
  while (num_finished < n)
  {
    std::vector<uint32_t> newly_covered;
    for (uint32_t v = 0; v < n; v++)
    {
      if (covered[v] == 0 && count[v] == 0)
      {
        in_mis[v] = 1;
        if (covered[v] == 0)
        {
          covered[v] = 1;
          newly_covered.push_back(v);
        }

        for (uint32_t i = G.offsets[v]; i < G.offsets[v + 1]; i++)
        {
          uint32_t u = G.neighbors[i];
          if (covered[u] == 0)
          {
            covered[u] = 1;
            newly_covered.push_back(u);
          }
        }
      }
    }

    if (newly_covered.empty())
      break;

    for (uint32_t v : newly_covered)
    {
      for (uint32_t i = G.offsets[v]; i < G.offsets[v + 1]; i++)
      {
        uint32_t u = G.neighbors[i];
        if (covered[u] == 0 && priority[v] > priority[u] && count[u] != 0)
          count[u]--;
      }
    }

    num_finished += (uint32_t)newly_covered.size();
  }
}

int main(int argc, char **argv)
{
  if (argc < 2)
  {
    std::cerr << "usage: " << argv[0] << " <graph.txt>\n";
    return 1;
  }

  Graph G;
  if (!loadUndirected(argv[1], G))
    return 1;

  const uint32_t n = G.num_vertices;
  std::vector<uint32_t> priority(n, 0);
  std::vector<uint32_t> expected(n, 0);
  for (uint32_t v = 0; v < n; v++)
    priority[v] = v;

  for (uint32_t v = 0; v < n; v++)
  {
    uint32_t count = 0;
    for (uint32_t i = G.offsets[v]; i < G.offsets[v + 1]; i++)
    {
      uint32_t u = G.neighbors[i];
      if (priority[u] > priority[v])
        count++;
    }
    expected[v] = count;
  }

  uint64_t cap = (uint64_t)G.neighbors.size() * sizeof(uint32_t) +
                 2ull * n * sizeof(uint64_t) +
                 6ull * n * sizeof(uint32_t) + 2ull * n +
                 sizeof(NGS_args) + sizeof(MIS_args) + sizeof(uint64_t) +
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

  Addr priority_base = hbm.alloc((uint64_t)n * sizeof(uint32_t));
  Addr count_base = hbm.alloc((uint64_t)n * sizeof(uint32_t));
  for (uint32_t v = 0; v < n; v++)
    hbm.ptr<uint32_t>(priority_base)[v] = priority[v];

  std::vector<uint8_t> expected_mis;
  referenceMIS(G, priority, expected_mis);

  Addr covered_base = hbm.alloc((uint64_t)n);
  Addr in_mis_base = hbm.alloc((uint64_t)n);
  Addr covered0_base = hbm.alloc((uint64_t)n * sizeof(uint32_t));
  Addr covered1_base = hbm.alloc((uint64_t)n * sizeof(uint32_t));
  Addr nextFChar_base = hbm.alloc(sizeof(uint64_t));
  Addr mis_cont_base = hbm.alloc(sizeof(MIS_args));

  hls::stream<MIS_args> misIn("misIn");
  hls::stream<NGS_args> ngsTasks("ngsTasks");
  hls::stream<NGS_args> ngsIn("ngsIn");
  hls::stream<mis_loop_helper_args> helperTasks("helperTasks");
  hls::stream<mis_loop_helper_args> helperIn("helperIn");
  hls::stream<uint64_t> ngsArgOut("ngsArgOut");
  hls::stream<uint64_t> misArgOut("misArgOut");
  hls::stream<lock_req> toLock0("toLock0");
  hls::stream<lock_resp> fromLock0("fromLock0");
  hls::stream<lock_req> toLock1("toLock1");
  hls::stream<lock_resp> fromLock1("fromLock1");
  hls::stream<lock_req> toLock2("toLock2");
  hls::stream<lock_resp> fromLock2("fromLock2");

  std::thread lockThread0(lockServer, &toLock0, &fromLock0, hbm.base());
  std::thread lockThread1(lockServer, &toLock1, &fromLock1, hbm.base());
  std::thread lockThread2(lockServer, &toLock2, &fromLock2, hbm.base());

  MIS_args root{};
  root.counter = 0;
  root.vertex_count = n;
  root.ngs_done = 0;
  root.active = 0;
  root.done = 0;
  root.num_finished = 0;
  root.last_covered_length = 0;
  root.loop_started = 0;
  root.graph = graph_base;
  root.priority = priority_base;
  root.nghCount = count_base;
  root.covered = covered_base;
  root.inMis = in_mis_base;
  root.covered0 = covered0_base;
  root.covered1 = covered1_base;
  root.nextFChar = nextFChar_base;
  root.cont = mis_cont_base;
  std::memcpy(hbm.ptr<uint8_t>(mis_cont_base), &root, sizeof(root));

  misIn.write(root);
  uint32_t launcher_calls = 0, ngs_run = 0, helpers_run = 0;
  bool watchdog = false;
  while (!misIn.empty())
  {
    if (launcher_calls >= n + 4)
    {
      watchdog = true;
      break;
    }

    MaximalIndependentSet(hbm.base(), ngsTasks, helperTasks, misIn);
    launcher_calls++;

    while (!ngsTasks.empty())
    {
      ngsIn.write(ngsTasks.read());
      NGS(hbm.base(), hbm.base(), hbm.base(), hbm.base(), ngsIn, ngsArgOut);
      ngs_run++;

      uint64_t cont = ngsArgOut.read();
      (*hbm.ptr<uint32_t>(cont))--;
    }

    while (!helperTasks.empty())
    {
      helperIn.write(helperTasks.read());
      mis_loop_helper(hbm.base(), hbm.base(), hbm.base(), hbm.base(), hbm.base(),
                      hbm.base(), hbm.base(),
                      helperIn, misArgOut, toLock0, fromLock0, toLock1,
                      fromLock1, toLock2, fromLock2);
      helpers_run++;

      uint64_t cont = misArgOut.read();
      (*hbm.ptr<uint32_t>(cont))--;
    }

    const MIS_args *cont = hbm.ptr<MIS_args>(mis_cont_base);
    if (cont->done)
      break;
    if (cont->counter == 0)
    {
      MIS_args next;
      std::memcpy(&next, cont, sizeof(next));
      misIn.write(next);
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

  uint32_t mis_mismatches = 0;
  for (uint32_t v = 0; v < n; v++)
  {
    uint8_t got = hbm.ptr<uint8_t>(in_mis_base)[v];
    if (got != expected_mis[v])
    {
      if (mis_mismatches < 20)
        std::cout << "[tester] MIS mismatch v=" << v << " got=" << (uint32_t)got
                  << " expected=" << (uint32_t)expected_mis[v] << "\n";
      mis_mismatches++;
    }
  }

  const MIS_args *mis_cont = hbm.ptr<MIS_args>(mis_cont_base);
  std::cout << "[tester] MIS launcher_calls=" << launcher_calls
            << " ngs_run=" << ngs_run
            << " helpers_run=" << helpers_run
            << " num_finished=" << mis_cont->num_finished << "\n";
  if (!watchdog && mis_mismatches == 0 && mis_cont->num_finished == n)
  {
    std::cout << "[tester] PASS: MIS loop matches golden.\n";
    return 0;
  }

  std::cout << "[tester] FAIL: MIS mismatches=" << mis_mismatches
            << " watchdog=" << watchdog << "\n";
  return 1;
}
