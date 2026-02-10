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

        // PRINT VERTICES SIZE
        std::cout << "Vertices size: " << vertices.size() << std::endl;

        // get the max vertex number from the vertices set and add the vertices from 0 to max
        int max_vertex = *vertices.rbegin();


        file.close();

        // create adjacency list
        create_adjacency_list(max_vertex);
        
        rename_verticies();
    }

    // Rename the vertices to be from 0 to num_vertices - 1
    void rename_verticies(){
        int new_vertex_index = 0;
        for (auto vertex : vertices){
            vertex_renaming_map[vertex] = new_vertex_index;
            flip_vertex_renaming_map[new_vertex_index] = vertex;
            new_vertex_index++;
        }

        for (auto edge : edges){
            renamed_edges.push_back({vertex_renaming_map[edge.first], vertex_renaming_map[edge.second]});
        }

        for(auto vertex : vertices){
            renamed_vertices.insert(vertex_renaming_map[vertex]);
        }

        // Create renamed adjacency list
        create_renamed_adjacency_list(new_vertex_index);
    }

    void create_renamed_adjacency_list(int max_vertex_index)
    {
        // resize the adjacency list to the max vertex index
        renamed_adjacency_list.resize(max_vertex_index + 1);

        for (uint32_t i = 0; i < renamed_edges.size(); i++)
        {
            renamed_adjacency_list[renamed_edges[i].first].insert(renamed_edges[i].second);
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

    const std::set<int> &getNeighborsRenamed(int u) const
    {
        // Check if the vertex has an entry in the adjacency list
        return renamed_adjacency_list[u];
    }

    int getOriginalVertex(int u) const
    {
        return flip_vertex_renaming_map.at(u);
    }

private:
    std::vector<std::pair<int, int>> edges;
    std::vector<std::set<int>> adjacency_list;
    std::set<int> vertices;
    
    std::vector<std::pair<int, int>> renamed_edges;
    std::set<int> renamed_vertices;
    std::vector<std::set<int>> renamed_adjacency_list;
    std::unordered_map<int, int> vertex_renaming_map;
    std::unordered_map<int, int> flip_vertex_renaming_map;
};

