#include "../graph.h"

int main() {
    Graph g = Graph("sample_graph.txt", false);
    Sequence<int> vertex_subset = {0, 1, 6, 11, 4, 3};
    Sequence<int> degrees(g.getNumVertices(), 0);
    for (int vertex : g.vertices) {
        degrees[vertex] = g.getDegree(vertex);
    }
    for (int vertex : vertex_subset) {
        degrees[vertex] = 0; 
    }
    Sequence<int> expected_degrees = degrees.clone();
    for (int vertex : vertex_subset) {
        for (auto neighbor : g.getNeighbors(vertex)) {
            expected_degrees[neighbor] = std::max(expected_degrees[neighbor] - 1, 0);
        }
    }

    auto condFn = [&](int vertex) {
      return true; // No condition, include all neighbors
    };
    auto updateFn = [&](int vertex, int edges_removed) {
      degrees[vertex] = std::max(degrees[vertex] - edges_removed, 0);
      return std::nullopt;
    };
    nghCount(g, vertex_subset, condFn, updateFn);

    for (int i = 0; i < g.getNumVertices(); ++i) {
        if (degrees[i] != expected_degrees[i]) {
            std::cerr << "Mismatch at vertex " << i << ": "
                      << degrees[i] << " != " << expected_degrees[i] << std::endl;
            return 1;
        }
    }
    std::cout << "NghCount test passed!" << std::endl;
    return 0;
}