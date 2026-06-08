#pragma once
// ─────────────────────────────────────────────────────────────────────────────
// graph.h — CSR graph loader + CPU-golden BFS for the HardCilk BFS host driver.
//
// This is the CPU reference from /beta/bradley/BFS/BFS.cpp, lifted into a
// header and made allocation-flexible (std::vector CSR instead of fixed arrays,
// no Cilk). `referenceBFS` produces exactly the distances the FPGA pipeline is
// expected to compute: source = 0, every BFS ring = parent_distance + 1,
// unreachable / beyond-max-depth vertices = -1.
// ─────────────────────────────────────────────────────────────────────────────

#include <cstdint>
#include <fstream>
#include <iostream>
#include <queue>
#include <sstream>
#include <string>
#include <vector>
#include <algorithm>

class Graph {
public:
  // Forward CSR: out-neighbors of u live in
  //   forward_neighbors[forward_offsets[u] .. forward_offsets[u+1]).
  std::vector<int> forward_neighbors;
  std::vector<int> forward_offsets;
  int num_vertices = 0;
  int num_edges = 0;

  Graph() = default;
  Graph(const std::string &filename, bool directed = true) {
    load(filename, directed);
  }

  static Graph twoMillionTwoLevelStar() {
    Graph G;
    constexpr int fanout = 2000000;
    G.num_vertices = fanout + 2;
    G.num_edges = 2 * fanout;
    std::vector<int> degree(G.num_vertices, 0);
    degree[0] = fanout;
    for (int i = 0; i < fanout; i++)
      degree[1 + i] = 1;

    G.forward_offsets.assign(G.num_vertices + 1, 0);
    for (int v = 0; v < G.num_vertices; v++)
      G.forward_offsets[v + 1] = G.forward_offsets[v] + degree[v];

    G.forward_neighbors.resize(G.num_edges);
    std::vector<int> cursor(G.forward_offsets.begin(), G.forward_offsets.end());
    const int sink = fanout + 1;
    auto add_edge = [&](int u, int v) {
      G.forward_neighbors[cursor[u]++] = v;
    };
    for (int i = 0; i < fanout; i++) {
      const int middle = 1 + i;
      add_edge(0, middle);
      add_edge(middle, sink);
    }
    return G;
  }

  static Graph twoMillionRing() {
    Graph G;
    constexpr int vertex_count = 2000000;
    G.num_vertices = vertex_count;
    G.num_edges = 2 * vertex_count;
    G.forward_offsets.resize(vertex_count + 1);
    for (int v = 0; v <= vertex_count; v++)
      G.forward_offsets[v] = 2 * v;

    G.forward_neighbors.resize(G.num_edges);
    std::vector<int> cursor(G.forward_offsets.begin(), G.forward_offsets.end());
    auto permute = [](int pos) {
      constexpr int vertex_count = 2000000;
      constexpr int multiplier = 1000003;
      constexpr int offset = 17;
      return (int)(((int64_t)pos * multiplier + offset) % vertex_count);
    };
    auto add_edge = [&](int u, int v) {
      G.forward_neighbors[cursor[u]++] = v;
    };
    for (int pos = 0; pos < vertex_count; pos++) {
      const int u = permute(pos);
      const int v = permute((pos + 1) % vertex_count);
      add_edge(u, v);
      add_edge(v, u);
    }
    return G;
  }

  static Graph fourMillionStar() {
    Graph G;
    constexpr int fanout = 4000000;
    G.num_vertices = fanout + 1;
    G.num_edges = fanout;

    G.forward_offsets.assign(G.num_vertices + 1, fanout);
    G.forward_offsets[0] = 0;

    G.forward_neighbors.resize(G.num_edges);
    for (int i = 0; i < fanout; i++)
      G.forward_neighbors[i] = i + 1;

    return G;
  }

  static Graph wikiMixed() {
    Graph G;
    const std::vector<int> level_size = {
        1, 20, 7529, 840007, 1520907, 20140, 342, 7};
    std::vector<int> level_base(level_size.size() + 1, 0);
    for (size_t i = 0; i < level_size.size(); i++)
      level_base[i + 1] = level_base[i] + level_size[i];

    G.num_vertices = level_base.back();
    G.num_edges =
        20 + 7529 + 840007 +
        1562310 + 2693292 + 1921021 +
        1921021 + 22198 + 20627 +
        342 + 7;

    std::vector<int> degree(G.num_vertices, 0);
    auto add_degrees = [&](int from_level, int edge_count) {
      const int from_base = level_base[from_level];
      const int from_count = level_size[from_level];
      for (int k = 0; k < edge_count; k++)
        degree[from_base + (k % from_count)]++;
    };

    add_degrees(0, 20);
    add_degrees(1, 7529);
    add_degrees(2, 840007);
    add_degrees(3, 1562310 + 2693292 + 1921021);
    add_degrees(4, 1921021 + 22198 + 20627);
    add_degrees(5, 342);
    add_degrees(6, 7);

    G.forward_offsets.assign(G.num_vertices + 1, 0);
    for (int v = 0; v < G.num_vertices; v++)
      G.forward_offsets[v + 1] = G.forward_offsets[v] + degree[v];

    G.forward_neighbors.resize(G.num_edges);
    std::vector<int> cursor(G.forward_offsets.begin(), G.forward_offsets.end());
    auto add_edges = [&](int from_level, int target_base, int target_count,
                         int edge_count, int target_stride, int target_offset) {
      const int from_base = level_base[from_level];
      const int from_count = level_size[from_level];
      for (int k = 0; k < edge_count; k++) {
        const int u = from_base + (k % from_count);
        const int v = target_base +
                      (int)(((int64_t)k * target_stride + target_offset) %
                            target_count);
        G.forward_neighbors[cursor[u]++] = v;
      }
    };
    auto add_next_edges = [&](int from_level, int edge_count) {
      add_edges(from_level, level_base[from_level + 1],
                level_size[from_level + 1], edge_count, 1, 0);
    };

    add_next_edges(0, 20);
    add_next_edges(1, 7529);
    add_next_edges(2, 840007);

    add_edges(3, level_base[0], level_base[3], 1562310, 1009, 17);
    add_edges(3, level_base[3], level_size[3], 2693292, 4099, 31);
    add_next_edges(3, 1921021);

    add_edges(4, level_base[0], level_base[4], 1921021, 1009, 17);
    add_edges(4, level_base[4], level_size[4], 22198, 4099, 31);
    add_next_edges(4, 20627);

    add_next_edges(5, 342);
    add_next_edges(6, 7);

    return G;
  }

  // Two-pass edge-list loader. `directed == false` inserts both directions,
  // matching the CPU golden's `G.load(path, false)` (the BFS frontier walks the
  // forward adjacency, so undirected graphs must store both halves).
  void load(const std::string &filename, bool directed = true) {
    std::ifstream file1(filename);
    if (!file1.is_open()) {
      std::cerr << "[graph] Unable to open file: " << filename << std::endl;
      return;
    }

    int max_vertex = -1;
    std::vector<int> degree;  // degree[v+1] accumulates out-degree of v
    std::string line;

    auto bump = [&](int v) {
      if (v + 1 >= (int)degree.size()) degree.resize(v + 2, 0);
    };

    // Pass 1: degrees + max vertex id.
    while (std::getline(file1, line)) {
      std::istringstream iss(line);
      int u, v;
      if (!(iss >> u >> v)) continue;
      max_vertex = std::max({max_vertex, u, v});
      bump(u);
      bump(v);
      degree[u + 1]++;
      num_edges++;
      if (!directed) {
        degree[v + 1]++;
        num_edges++;
      }
    }
    file1.close();

    num_vertices = max_vertex + 1;
    forward_offsets.assign(num_vertices + 1, 0);
    for (int i = 0; i < num_vertices && i + 1 < (int)degree.size(); i++)
      forward_offsets[i + 1] = degree[i + 1];
    for (int i = 1; i <= num_vertices; i++)
      forward_offsets[i] += forward_offsets[i - 1];

    forward_neighbors.assign(num_edges, 0);
    std::vector<int> cursor(forward_offsets.begin(), forward_offsets.end());

    // Pass 2: scatter.
    std::ifstream file2(filename);
    while (std::getline(file2, line)) {
      std::istringstream iss(line);
      int u, v;
      if (!(iss >> u >> v)) continue;
      forward_neighbors[cursor[u]++] = v;
      if (!directed) forward_neighbors[cursor[v]++] = u;
    }
    file2.close();
  }

  int getNumVertices() const { return num_vertices; }
  int getDegree(int u) const { return forward_offsets[u + 1] - forward_offsets[u]; }
};

// Serial, level-synchronous BFS — identical distances to the parallel atomic
// version in the CPU golden (BFS distance is order-independent). Vertices whose
// shortest-path distance exceeds `max_depth` stay at -1, matching the HLS
// `currentDistance > max_depth` cutoff.
inline void referenceBFS(const Graph &G, int source, std::vector<int> &dist_out,
                         int max_depth) {
  int n = G.getNumVertices();
  dist_out.assign(n, -1);
  if (source < 0 || source >= n) return;

  dist_out[source] = 0;
  std::queue<int> q;
  q.push(source);
  while (!q.empty()) {
    int u = q.front();
    q.pop();
    int du = dist_out[u];
    if (du >= max_depth) continue;  // children would land at du+1 > max_depth
    for (int j = G.forward_offsets[u]; j < G.forward_offsets[u + 1]; j++) {
      int w = G.forward_neighbors[j];
      if (w >= 0 && w < n && dist_out[w] == -1) {
        dist_out[w] = du + 1;
        q.push(w);
      }
    }
  }
}

inline void print_bfs_summary(const int *dist, int n, const char *label) {
  int reached = 0, max_dist = -1;
  for (int v = 0; v < n; v++)
    if (dist[v] >= 0) {
      reached++;
      max_dist = std::max(max_dist, dist[v]);
    }

  std::cout << "=== " << label << " ===\n";
  std::cout << "Reached: " << reached << " / " << n << "\n";
  std::cout << "Max distance: " << max_dist << "\n";

  std::vector<int> level_count(max_dist + 2, 0);
  for (int v = 0; v < n; v++)
    if (dist[v] >= 0) level_count[dist[v]]++;
  std::cout << "Vertices per level:\n";
  for (int d = 0; d <= max_dist; d++)
    std::cout << "  d=" << d << ": " << level_count[d] << "\n";

  std::cout << "First 20 distances:";
  for (int i = 0; i < std::min(20, n); i++)
    std::cout << " v" << i << "=" << dist[i];
  std::cout << std::endl;
}
