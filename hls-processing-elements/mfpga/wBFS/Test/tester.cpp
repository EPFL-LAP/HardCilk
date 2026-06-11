
#include "util.h"

#include <cstdint>
#include <cstring>
#include <fstream>
#include <iostream>
#include <queue>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

void wBFS(void *mem_0, hls::stream<sparse_edgemap_helper_args> &taskOutGlobal,
          hls::stream<wBFS_args> &taskIn);
void sparse_edgemap_helper(void *mem_0, void *mem_1, void *mem_2, void *mem_3,
                           void *mem_4, void *mem_5,
                           hls::stream<sparse_edgemap_helper_args> &taskIn,
                           hls::stream<uint64_t> &argOut,
                           hls::stream<lock_req> &toLock1,
                           hls::stream<lock_resp> &fromLock1,
                           hls::stream<lock_req> &toLock2,
                           hls::stream<lock_resp> &fromLock2);

static const char *DEFAULT_GRAPH = "/beta/bradley/Graphs/tinyGraph.txt";

// Opcode used only by this harness to tell the lock thread to exit.
static const uint8_t LOCK_OP_STOP = 0xF;

// ─────────────────────────────────────────────────────────────────────────────
// CSR graph: undirected edge-list loader (matches the driver's Graph(file,false))
// plus a textbook reference BFS for the golden distances.
// ─────────────────────────────────────────────────────────────────────────────
struct Graph
{
  int num_vertices = 0;
  int num_edges = 0;
  std::vector<int> forward_offsets; // size num_vertices + 1
  std::vector<int> forward_neighbors;

  int degree(int u) const { return forward_offsets[u + 1] - forward_offsets[u]; }
};

static bool loadUndirected(const std::string &path, Graph &G)
{
  std::ifstream f1(path);
  if (!f1.is_open())
  {
    std::cerr << "[tester] cannot open graph: " << path << "\n";
    return false;
  }

  int max_vertex = -1;
  std::vector<int> degree; // degree[v+1] accumulates out-degree of v
  std::string line;
  auto bump = [&](int v)
  {
    if (v + 1 >= (int)degree.size())
      degree.resize(v + 2, 0);
  };

  // Pass 1: degrees + max id. Undirected => count both directions.
  while (std::getline(f1, line))
  {
    std::istringstream iss(line);
    int u, v;
    if (!(iss >> u >> v))
      continue;
    max_vertex = std::max(max_vertex, std::max(u, v));
    bump(u);
    bump(v);
    degree[u + 1]++;
    degree[v + 1]++;
    G.num_edges += 2;
  }
  f1.close();

  G.num_vertices = max_vertex + 1;
  G.forward_offsets.assign(G.num_vertices + 1, 0);
  for (int i = 0; i < G.num_vertices && i + 1 < (int)degree.size(); i++)
    G.forward_offsets[i + 1] = degree[i + 1];
  for (int i = 1; i <= G.num_vertices; i++)
    G.forward_offsets[i] += G.forward_offsets[i - 1];

  G.forward_neighbors.assign(G.num_edges, 0);
  std::vector<int> cursor(G.forward_offsets.begin(), G.forward_offsets.end());

  // Pass 2: scatter both directions.
  std::ifstream f2(path);
  while (std::getline(f2, line))
  {
    std::istringstream iss(line);
    int u, v;
    if (!(iss >> u >> v))
      continue;
    G.forward_neighbors[cursor[u]++] = v;
    G.forward_neighbors[cursor[v]++] = u;
  }
  f2.close();
  return true;
}

static void referencewBFS(const Graph &G, int source, int max_depth,
                          std::vector<int> &dist)
{
  /* TODO */
}

// ─────────────────────────────────────────────────────────────────────────────
// Flat "HBM": a byte buffer plus a 512-aligned bump allocator. Returned values
// are byte offsets, which is exactly what the kernels treat as addresses.
// ─────────────────────────────────────────────────────────────────────────────
struct Hbm
{
  std::vector<uint8_t> mem;
  uint64_t off = 512; // leave the first 512 bytes unused (avoid "address 0")

  explicit Hbm(uint64_t cap) : mem(cap, 0) {}
  uint8_t *base() { return mem.data(); }

  uint64_t alloc(uint64_t bytes, uint64_t align = 512)
  {
    off = (off + align - 1) / align * align;
    uint64_t a = off;
    off += bytes;
    if (off > mem.size())
    {
      std::cerr << "[tester] HBM model out of space\n";
      std::abort();
    }
    return a;
  }

  template <typename T>
  T *ptr(uint64_t addr)
  {
    return reinterpret_cast<T *>(base() + addr);
  }
};

// ─────────────────────────────────────────────────────────────────────────────
// Trivial single-PE lock: always succeeds, just applies the atomic op. Runs on
// its own thread so the helper's blocking request/response handshake completes.
// ─────────────────────────────────────────────────────────────────────────────
static void lockServer(hls::stream<lock_req> *toLock,
                       hls::stream<lock_resp> *fromLock, uint8_t *mem)
{
  for (;;)
  {
    lock_req req = toLock->read(); // blocking
    uint8_t op = (uint8_t)req.data(131, 128);
    if (op == LOCK_OP_STOP)
      break;

    uint64_t addr = (uint64_t)req.data(63, 0);
    uint64_t value = (uint64_t)req.data(127, 64);
    uint8_t mode = (uint8_t)req.data(134, 133);
    uint64_t prev = 0;

    if (op == LOCK_OP_SET_AND_RETURN_CURRENT)
    {
      if (mode == ATOMIC_MODE_BYTE)
      {
        uint8_t *p = mem + addr;
        prev = *p;
        *p = (uint8_t)value;
      }
      else if (mode == ATOMIC_MODE_WORD)
      {
        uint32_t *p = reinterpret_cast<uint32_t *>(mem + addr);
        prev = *p;
        *p = (uint32_t)value;
      }
      else
      { // doubleword
        uint64_t *p = reinterpret_cast<uint64_t *>(mem + addr);
        prev = *p;
        *p = value;
      }
    }
    else if (op == LOCK_OP_ADD_N_RETURN_CURRENT)
    {
      // Reserve next-frontier slots: return the current counter, then advance it
      // by the operand N (the add-N atomic; N is whatever the PE put in the data
      // field, typically the number of slots it wants to claim at once).
      uint64_t *p = reinterpret_cast<uint64_t *>(mem + addr);
      prev = *p;
      *p = prev + value;
    }
    else if (op == LOCK_OP_SET_IF_LESS_AND_RETURN_CURRENT)
    {
      uint64_t *p = reinterpret_cast<uint64_t *>(mem + addr);
      prev = *p;
      if (prev < value)
        *p = value;
    }
    else
    {
      // No other opcodes are used by BFS; echo current doubleword.
      prev = *reinterpret_cast<uint64_t *>(mem + addr);
    }

    lock_resp resp;
    resp.data = 0;
    resp.data(63, 0) = 1;      // status: success
    resp.data(127, 64) = prev; // previous contents (right-justified by the AMU)
    resp.keep = -1;
    resp.strb = -1;
    resp.last = 1;
    fromLock->write(resp);
  }
}

// ─────────────────────────────────────────────────────────────────────────────
int main(int argc, char **argv)
{
  const std::string graph_file = (argc > 1) ? argv[1] : DEFAULT_GRAPH;
  const int source = (argc > 2) ? std::atoi(argv[2]) : 0;

  Graph G;
  if (!loadUndirected(graph_file, G))
    return 1;
  const int n = G.num_vertices;
  if (n <= 0)
  {
    std::cerr << "[tester] empty graph\n";
    return 1;
  }
  const int max_depth = n; // unbounded for this size

  std::cout << "[tester] graph=" << graph_file << " vertices=" << n
            << " edges=" << G.num_edges << " source=" << source
            << " max_depth=" << max_depth << "\n";

  // ── Size and lay out the flat HBM, byte-identical to the driver ───────────
  const uint64_t neighborBytes =
      std::max<uint64_t>(G.forward_neighbors.size() * sizeof(uint32_t),
                         sizeof(uint32_t));
  uint64_t cap = neighborBytes + 2ull * n * sizeof(uint64_t) +
                 (uint64_t)n * sizeof(int32_t) + (uint64_t)n +
                 2ull * n * sizeof(uint32_t) + sizeof(uint64_t) +
                 sizeof(wBFS_args);
  cap = cap * 2 + (1u << 20); // generous slack for 512-byte alignment + margin
  Hbm hbm(cap);

  // neighbor id stream (uint32), in forward_neighbors order.
  Addr neighbors_base = hbm.alloc(neighborBytes);
  for (size_t i = 0; i < G.forward_neighbors.size(); i++)
    hbm.ptr<uint32_t>(neighbors_base)[i] = (uint32_t)G.forward_neighbors[i];

  // graph[u] = { neighbors_ptr, degree } — 16 bytes, indexed by (u << 4).
  Addr graph_base = hbm.alloc(2ull * n * sizeof(uint64_t));
  for (int u = 0; u < n; u++)
  {
    uint64_t off = (uint64_t)G.forward_offsets[u] * sizeof(uint32_t);
    hbm.ptr<uint64_t>(graph_base)[2 * u + 0] = neighbors_base + off;
    hbm.ptr<uint64_t>(graph_base)[2 * u + 1] = (uint64_t)G.degree(u);
  }

  Addr distance_base = hbm.alloc((uint64_t)n * sizeof(int32_t));
  Addr visited_base = hbm.alloc((uint64_t)n * VISITED_SLOT_BYTES);
  Addr frontier0_base = hbm.alloc((uint64_t)n * sizeof(uint32_t));
  Addr frontier1_base = hbm.alloc((uint64_t)n * sizeof(uint32_t));
  Addr nextFChar_base = hbm.alloc(sizeof(uint64_t));
  Addr cont_base = hbm.alloc(sizeof(wBFS_args));

  // Sentinel init (the PE's init() repeats this, but keep the buffers defined).
  for (int v = 0; v < n; v++)
    hbm.ptr<int32_t>(distance_base)[v] = -1;
  // visited / nextFChar already zero from the buffer's value-init.

  // ── Build + seed the root BFS task (init path: currentDistance==0 && len==0)
  wBFS_args root{};
  root.counter = 0;
  root.source = (uint32_t)source;
  root.vertex_count = (uint32_t)n;
  root.currentDistance = 0;
  root.max_depth = (uint32_t)max_depth;
  root.frontier_length = 0;
  root.active = 0;
  root.done = 0;
  root.graph = graph_base;
  root.distance = distance_base;
  root.visited = visited_base;
  root.frontier0 = frontier0_base;
  root.frontier1 = frontier1_base;
  root.nextFChar = nextFChar_base;
  root.cont = cont_base;
  std::memcpy(hbm.ptr<uint8_t>(cont_base), &root, sizeof(root));

  // ── Streams + the trivial lock thread ─────────────────────────────────────
  hls::stream<wBFS_args> bfs_in("bfs_in");
  hls::stream<sparse_edgemap_helper_args> helper_tasks("helper_tasks");
  hls::stream<sparse_edgemap_helper_args> helper_in("helper_in");
  hls::stream<uint64_t> argOut("argOut");
  hls::stream<lock_req> toLock("toLock");
  hls::stream<lock_resp> fromLock("fromLock");

  std::thread lockThread(lockServer, &toLock, &fromLock, hbm.base());

  // BFS_new's helper uses a second, independent lock server for the nextFChar
  // slot-reservation counter. Same trivial responder, its own thread, same
  // backing buffer (the two servers never touch the same address).
  hls::stream<lock_req> toLock2("toLock2");
  hls::stream<lock_resp> fromLock2("fromLock2");
  std::thread lockThread2(lockServer, &toLock2, &fromLock2, hbm.base());

  // ── Drive the framework loop until the PE sets done ───────────────────────
  bfs_in.write(root);
  uint64_t rounds = 0, helpers_run = 0;
  while (!bfs_in.empty())
  {
    wBFS(hbm.base(), helper_tasks, bfs_in); // consumes one BFS task
    rounds++;

    // Gather every helper task this BFS invocation spawned and run them one at
    // a time against the single helper instance.
    while (!helper_tasks.empty())
    {
      helper_in.write(helper_tasks.read());
      sparse_edgemap_helper(hbm.base(), hbm.base(), hbm.base(), hbm.base(),
                            hbm.base(), hbm.base(), helper_in, argOut, toLock,
                            fromLock, toLock2, fromLock2);

      helpers_run++;

      // ArgumentNotifier: each finished helper decrements the continuation's
      // join counter (field 0 of wBFS_args at its cont address).
      uint64_t cont_addr = argOut.read();
      (*hbm.ptr<uint32_t>(cont_addr))--;
    }

    const wBFS_args *cont = hbm.ptr<wBFS_args>(cont_base);
    if (cont->done)
      break;
    if (cont->counter == 0)
    {
      // Join counter hit zero: re-inject the continuation as the next BFS task.
      wBFS_args next;
      std::memcpy(&next, cont, sizeof(next));
      bfs_in.write(next);
    }
  }

  // Stop the lock thread(s).
  toLock.write(make_lock_req(0, 0, (LockOperation)LOCK_OP_STOP, false,
                             ATOMIC_MODE_DOUBLEWORD));
  lockThread.join();
#ifdef BFS_NEW
  toLock2.write(make_lock_req(0, 0, (LockOperation)LOCK_OP_STOP, false,
                              ATOMIC_MODE_DOUBLEWORD));
  lockThread2.join();
#endif

  std::cout << "[tester] BFS rounds=" << rounds
            << " helpers_run=" << helpers_run << "\n";

  // ── Read back FPGA-model distances and compare to the reference ───────────
  std::vector<int> dist_fpga(n);
  for (int v = 0; v < n; v++)
    dist_fpga[v] = hbm.ptr<int32_t>(distance_base)[v];

  std::vector<int> dist_ref;
  referencewBFS(G, source, max_depth, dist_ref);

  int mismatches = 0;
  for (int v = 0; v < n; v++)
  {
    if (dist_fpga[v] != dist_ref[v])
    {
      if (mismatches < 20)
        std::cerr << "[tester] MISMATCH v=" << v << " kernel=" << dist_fpga[v]
                  << " ref=" << dist_ref[v] << "\n";
      mismatches++;
    }
  }

  int reached = 0, max_dist = -1;
  for (int v = 0; v < n; v++)
    if (dist_fpga[v] >= 0)
    {
      reached++;
      max_dist = std::max(max_dist, dist_fpga[v]);
    }
  std::cout << "[tester] reached=" << reached << "/" << n
            << " max_distance=" << max_dist << "\n";

  if (mismatches == 0)
  {
    std::cout << "[tester] PASS — kernel distances match reference BFS.\n";
    return 0;
  }
  std::cerr << "[tester] FAIL — " << mismatches << " mismatching vertices.\n";
  return 1;
}
