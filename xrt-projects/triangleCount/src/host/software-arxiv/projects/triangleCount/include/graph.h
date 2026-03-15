#include <iostream>
#include <vector>
#include <set>
#include <utility>
#include <fstream>
#include <sstream>
#include <mutex>
#include <chrono>
#include <algorithm>
#include <queue>
#include <unordered_map>

class Graph
{
public:
    Graph() = default;

    Graph(const std::string &filename, bool directed = true)
    {
        std::ifstream file(filename);
        if (!file.is_open())
        {
            std::cerr << "Unable to open file: " << filename << std::endl;
            return;
        }

        std::string line;
        while (std::getline(file, line))
        {
            std::istringstream iss(line);
            int u, v;
            if (!(iss >> u >> v))
            {
                std::cerr << "Invalid line format: " << line << std::endl;
                continue;
            }
            addEdge(u, v);
            if (!directed)
            {
                addEdge(v, u);
            }
        }

        // get the max vertex number from the vertices set and add the vertices from 0 to max
        int max_vertex = *vertices.rbegin();
        set_vertex_count_as_max_vertex();

        file.close();

        // create adjacency list
        create_adjacency_list(max_vertex);
    }

    void set_vertex_count_as_max_vertex()
    {
        int max_vertex = *vertices.rbegin();
        for (int i = 0; i <= max_vertex; i++)
        {
            vertices.insert(i);
        }
    }

    void create_adjacency_list(int max_vertex_index)
    {
        // resize the adjacency list to the max vertex index
        adjacency_list.resize(max_vertex_index + 1);

        for (uint32_t i = 0; i < edges.size(); i++)
        {
            adjacency_list[edges[i].first].insert(edges[i].second);
        }
    }

    void addEdge(int u, int v)
    {
        edges.push_back({u, v});
        vertices.insert(u);
        vertices.insert(v);
    }

    void printGraph() const
    {
        for (const auto &edge : edges)
        {
            std::cout << edge.first << " -- " << edge.second << std::endl;
        }
    }

    int getNumVertices() const
    {
        return vertices.size();
    }

    int getNumNonEmpty() {
        int count = 0;
        for(int i = 0; i < vertices.size(); i++){
            if(getNeighbors(i).size() != 0){
                count++;
            }
        }
        return count;
    }

    int getDegree(int u) const
    {
        // Check if the vertex has an entry in the adjacency list
        return adjacency_list[u].size();
    }

    const std::set<int> &getNeighbors(int u) const
    {
        // Check if the vertex has an entry in the adjacency list
        return adjacency_list[u];
    }

private:
    std::vector<std::pair<int, int>> edges;
    std::vector<std::set<int>> adjacency_list;
    std::set<int> vertices;
    std::set<int> empty_set;
};

bool filterEdge(int vertexA, int vertexB, Graph &g){
  int degreeA = g.getDegree(vertexA);
  int degreeB = g.getDegree(vertexB);
  return degreeA > degreeB || (degreeA == degreeB && vertexA > vertexB);
}

// Takes a graph and a filter function and returns a new directed graph with the filtered edges
void filterGraph(Graph &g, Graph &new_graph, bool (*filter)(int, int, Graph &))
{
    for (int i = 0; i < g.getNumVertices(); i++)
    {
        for (auto neighbor : g.getNeighbors(i))
        {
            if (filter(i, neighbor, g))
            {
                new_graph.addEdge(neighbor , i);
            }
        }
    }
    new_graph.set_vertex_count_as_max_vertex();
    new_graph.create_adjacency_list(g.getNumVertices());
}



// #include <algorithm>
// #include <fstream>
// #include <iostream>
// #include <string>
// #include <utility>
// #include <vector>

// class Graph {
// public:
//     Graph() = default;

//     // Load graph and compact vertex IDs
//     Graph(const std::string& filename, bool directed = true) {
//         std::ifstream file(filename);
//         if (!file.is_open()) {
//             std::cerr << "Unable to open file: " << filename << "\n";
//             return;
//         }

//         std::vector<std::pair<int, int>> raw_edges;
//         raw_edges.reserve(1024);

//         int u, v;
//         int max_id = -1;

//         while (file >> u >> v) {
//             raw_edges.emplace_back(u, v);
//             max_id = std::max({max_id, u, v});
//             if (!directed) {
//                 raw_edges.emplace_back(v, u);
//             }
//         }

//         // Compact IDs
//         std::vector<int> old_to_new(max_id + 1, -1);
//         int next_id = 0;

//         for (const auto& e : raw_edges) {
//             if (old_to_new[e.first] == -1) {
//                 old_to_new[e.first] = next_id++;
//             }
//             if (old_to_new[e.second] == -1) {
//                 old_to_new[e.second] = next_id++;
//             }
//         }

//         num_vertices = next_id;
//         adjacency_list.resize(num_vertices);

//         for (const auto& e : raw_edges) {
//             adjacency_list[old_to_new[e.first]].push_back(
//                 old_to_new[e.second]
//             );
//         }

//         sortNeighbors();
//     }

//     void addEdge(int u, int v) {
//         if (u >= num_vertices) {
//             num_vertices = u + 1;
//             adjacency_list.resize(num_vertices);
//         }
//         adjacency_list[u].push_back(v);
//     }

//     int getNumVertices() const {
//         return num_vertices;
//     }

//     int getDegree(int u) const {
//         return adjacency_list[u].size();
//     }

//     const std::vector<int>& getNeighbors(int u) const {
//         return adjacency_list[u];
//     }

//     // REQUIRED by existing system
//     int getNumNonEmpty() const {
//         int count = 0;
//         for (const auto& neighbors : adjacency_list) {
//             if (!neighbors.empty()) {
//                 ++count;
//             }
//         }
//         return count;
//     }

//     void sortNeighbors() {
//         for (auto& neighbors : adjacency_list) {
//             std::sort(neighbors.begin(), neighbors.end());
//         }
//     }

// private:
//     std::vector<std::vector<int>> adjacency_list;
//     int num_vertices = 0;
// };

// bool filterEdge(int vertexA, int vertexB, Graph& g) {
//     int degreeA = g.getDegree(vertexA);
//     int degreeB = g.getDegree(vertexB);

//     return degreeA > degreeB ||
//            (degreeA == degreeB && vertexA > vertexB);
// }

// // API-compatible wrapper expected by your system
// void filterGraph(
//     Graph& g,
//     Graph& directed,
//     bool (*filter)(int, int, Graph&)
// ) {
//     int n = g.getNumVertices();

//     std::vector<int> old_to_new(n, -1);
//     std::vector<std::pair<int, int>> filtered_edges;
//     filtered_edges.reserve(1024);

//     // Filter edges
//     for (int u = 0; u < n; ++u) {
//         for (int v : g.getNeighbors(u)) {
//             if (filter(u, v, g)) {
//                 filtered_edges.emplace_back(v, u);
//                 old_to_new[u] = 0;
//                 old_to_new[v] = 0;
//             }
//         }
//     }

//     // Compact IDs
//     int next_id = 0;
//     for (int i = 0; i < n; ++i) {
//         if (old_to_new[i] == 0) {
//             old_to_new[i] = next_id++;
//         }
//     }

//     // Build directed graph
//     for (const auto& e : filtered_edges) {
//         directed.addEdge(
//             old_to_new[e.first],
//             old_to_new[e.second]
//         );
//     }

//     directed.sortNeighbors();
// }