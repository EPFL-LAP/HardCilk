#include "graph.h"

std::set<int> approximate_densest_subgraph(Graph &g, int e) {
  // return g.getNeighbors(
  //     e); // This is a placeholder; actual implementation will vary
}

int main(int argc, char *argv[]) {
  if (argc < 2) {
    std::cerr << "Usage: " << argv[0] << " <graph_file> <optional epsilon>" << std::endl;
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

  std::set<int> densest_subgraph = approximate_densest_subgraph(g, epsilon);

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