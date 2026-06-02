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
