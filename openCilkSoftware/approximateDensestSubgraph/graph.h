#pragma once
#include "cilk/cilk.h"
#include "sequence.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <fstream>
#include <functional>
#include <iostream>
#include <mutex>
#include <optional>
#include <queue>
#include <set>
#include <sstream>
#include <unordered_map>
#include <utility>
#include <vector>

class Graph {
public:
  Graph() = default;

  Graph(const std::string &filename, bool directed = true) {
    int max_vertex = 0;
    std::ifstream file(filename);
    if (!file.is_open()) {
      std::cerr << "Unable to open file: " << filename << std::endl;
      return;
    }

    std::string line;
    while (std::getline(file, line)) {
      std::istringstream iss(line);
      int u, v;
      if (!(iss >> u >> v)) {
        std::cerr << "Invalid line format: " << line << std::endl;
        continue;
      }
      addEdge(u, v);
      if (!directed) {
        addEdge(v, u);
      }
      max_vertex = std::max(max_vertex, std::max(u, v));
    }

    // Initialize vertices from 0 to max_vertex
    vertices = Sequence<int>(max_vertex + 1, [&](int index) { return index; });

    file.close();

    // create adjacency list
    create_adjacency_list(max_vertex);
  }

  void create_adjacency_list(int max_vertex_index) {
    // resize the adjacency list to the max vertex index
    adjacency_list.resize(max_vertex_index + 1);

    for (uint32_t i = 0; i < edges.size(); i++) {
      // SAFETY: Ensure that the edges are unique
      adjacency_list[edges[i].first].push_back(edges[i].second);
    }
  }

  void addEdge(int u, int v) { edges.push_back({u, v}); }

  void printGraph() const {
    for (const auto &edge : edges) {
      std::cout << edge.first << " -- " << edge.second << std::endl;
    }
  }

  int getNumVertices() const { return vertices.size(); }

  int getDegree(int u) {
    // Check if the vertex has an entry in the adjacency list
    return adjacency_list[u].size();
  }

  Sequence<int> &getNeighbors(int u) {
    // Check if the vertex has an entry in the adjacency list
    return adjacency_list[u];
  }

public:
  std::vector<std::pair<int, int>> edges;
  Sequence<Sequence<int>> adjacency_list;
  Sequence<int> vertices;
};

void vertexMap(Sequence<int> &vertices, std::function<void(int &)> func) {
  vertices.map(
      [&](int &vertex, size_t index, size_t work_id) { func(vertex); });
}

/**
 * @brief Filters a subset of vertices based on a predicate.
 *
 * @param vertices The vector of vertices.
 * @param filter The predicate function to apply to each vertex.
 * @return std::vector<int> A vector containing the filtered vertices.
 */
Sequence<int> vertexSubset(Sequence<int> &vertices,
                           std::function<bool(int)> filter) {
  return vertices.subset(filter);
}

template <typename R>
Sequence<int> nghReduce(Graph &g, Sequence<int> &vertex_subset,
                        std::function<R(int, int)> mapFn, R default_value,
                        std::function<bool(int)> condFn, Monoid<R> reduceFn,
                        std::function<std::optional<int>(int, R)> updateFn) {
  // 1. Parallel for over all u,v pairs and their mapped value
  // 2. Sort?
  // 3. Histogram?
}

void nghCount(Graph &g, Sequence<int> &vertex_subset,
              std::function<bool(int)> condFn,
              std::function<std::optional<int>(int, int)> updateFn) {
  nghReduce<int>(
      g, vertex_subset,
      [&](int vertex, int neighbor) {
        return 1; // Count each edge
      },
      0, condFn, Monoid<int>(0, [](int a, int b) { return a + b; }), updateFn);
}

int density(Sequence<int> &degrees, Sequence<int> &vertex_subset) {
  return degrees.reduce(Monoid<int>(0, [](int a, int b) { return a + b; })) /
         vertex_subset.size();
}