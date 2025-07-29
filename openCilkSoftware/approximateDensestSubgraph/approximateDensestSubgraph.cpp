#include "graph.h"
#include "sequence.h"
#include <optional>

Sequence<int> approximate_densest_subgraph(Graph &g, int e) {
  Sequence<int> degrees(g.getNumVertices(), 0);
  Sequence<int> S = g.vertices.clone();
  Sequence<int> S_max;
  S.map([&](int vertex, size_t index, size_t work_id) {
    degrees[index] = g.getDegree(vertex);
  });
  while (S.size() > 0) {
    int density_val = density(degrees, S);
    printf("Current Density: %d, S size: %zu\n", density_val, S.size());
    auto R = vertexSubset(S, [&](int vertex) {
      return degrees[vertex] < 2 * density_val * (1 + e);
    });
    vertexMap(R, [&](int &vertex) { degrees[vertex] = 0; });
    auto condFn = [&](int vertex) {
      return true; // No condition, include all neighbors
    };
    auto updateFn = [&](int vertex, int edges_removed) {
      degrees[vertex] = std::max(degrees[vertex] - edges_removed, 0);
      return std::nullopt;
    };
    nghCount(g, R, condFn, updateFn);
    S = S.subset([&](int vertex) {
      return degrees[vertex] >= 2 * density_val * (1 + e);
    });
    if (density(degrees, S) > density_val) {
      S_max = S.clone();
    }
  }
  return S_max;
}

int main(int argc, char *argv[]) {
  if (argc < 2) {
    std::cerr << "Usage: " << argv[0] << " <graph_file> <optional epsilon>"
              << std::endl;
    return 1;
  }
  std::string graph_file = argv[1];
  double epsilon = 0.001; // Default epsilon value
  if (argc > 2) {
    epsilon = std::stod(argv[2]);
  }

  Graph g(graph_file, false);

  std::cout << "Done Reading Graph" << std::endl;
  auto start = std::chrono::high_resolution_clock::now();

  auto densest_subgraph = approximate_densest_subgraph(g, epsilon);

  auto end = std::chrono::high_resolution_clock::now();
  std::cout << "Done Finding Densest Subgraph in "
            << std::chrono::duration_cast<std::chrono::milliseconds>(end -
                                                                     start)
                   .count()
            << " ms" << std::endl;
  std::cout << "Densest Subgraph Vertices: ";
  for (const auto &v : densest_subgraph) {
    std::cout << v << " ";
  }
  std::cout << std::endl;

  return 0;
}