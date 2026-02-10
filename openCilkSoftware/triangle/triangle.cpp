#include <iostream>
#include <vector>
#include <set>
#include <utility>
#include <fstream>
#include <sstream>
#include <cilk/cilk.h>
#include <mutex>
#include <chrono>
#include <algorithm>
#include <queue>
#include <unordered_map>
#include <cstdio>
#include <cstdlib>
#include <cstring>

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
        for (int i = 0; i <= max_vertex; i++)
        {
            vertices.insert(i);
        }

        file.close();

        // create adjacency list
        create_adjacency_list(max_vertex);
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

private:
    std::vector<std::pair<int, int>> edges;
    std::vector<std::set<int>> adjacency_list;
    std::set<int> vertices;
    std::set<int> empty_set;
};

bool filterEdge(int vertexA, int vertexB, Graph &g)
{
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
                new_graph.addEdge(i, neighbor);
            }
        }
    }
    new_graph.create_adjacency_list(g.getNumVertices());
}

int countIntersection(Graph &g, int vertexA, int vertexB)
{
    int count = 0;
    // for (auto neighbor : g.getNeighbors(vertexA))
    // {
    //     if (g.getNeighbors(vertexB).find(neighbor) != g.getNeighbors(vertexB).end())
    //     {
    //         count++;
    //     }
    // }
    std::set<int> intersection;
    std::set_intersection(g.getNeighbors(vertexA).begin(), g.getNeighbors(vertexA).end(), g.getNeighbors(vertexB).begin(), g.getNeighbors(vertexB).end(), std::inserter(intersection, intersection.begin()));

    return intersection.size();
}




void vertexMap(Graph &g, std::vector<int> &traingle_count_arr, int vertex)
{
    int count = 0;
    for (auto neighbor : g.getNeighbors(vertex))
    {
        count += countIntersection(g, vertex, neighbor);
    }
    traingle_count_arr[vertex] = count;
}

void countTriangles(Graph &g, std::vector<int> &traingle_count_arr)
{
    for (int i = 0; i < g.getNumVertices(); i++)
    {
        cilk_spawn vertexMap(g, traingle_count_arr, i);
    }
    cilk_sync;
}

int main()
{
    // Graph g("/home/shahawy/graphs/single_triangle.txt", false);
    //Graph g("/home/shahawy/email-EuAll.txt", false);
    //Graph g("/home/shahawy/graphs/soc-LiveJournal1.txt", false);
    Graph g("/home/shahawy/graphs/congress_r.txt", false);
    //Graph g("/home/shahawy/graphs/com-orkut.ungraph.txt", false);
    //Graph g("/home/shahawy/congress_network/congress.edgelist", false);
    //Graph g("/home/shahawy/congress_network/congress_reduced.txt", false);
    //Graph g("/home/shahawy/p2p-Gnutella31.txt", false);
    // Graph g ("/home/shahawy/graphs/twitter_combined.txt");
    // Graph g("/home/shahawy/graphs/soc-LiveJournal1.txt", false);
    // Graph g ("/home/shahawy/opencilk_ws/benchmarks/triangle/example_graph.txt", false);
    std::cout << "Done Reading Graph" << std::endl;

    // Print verticies count
    std::cout << "Number of vertices: " << g.getNumVertices() << std::endl;

    Graph directed;
    filterGraph(g, directed, filterEdge);

    // In the filtered graph, find the node with the highest degree
    int max_degree = 0;
    int max_degree_vertex = 0;
    for (int i = 0; i < directed.getNumVertices(); i++)
    {
        int degree = directed.getDegree(i);
        if (degree > max_degree)
        {
            max_degree = degree;
            max_degree_vertex = i;
        }
    }

    // Print the vertex with the highest degree and its degree
    std::cout << "Vertex with the highest degree: " << max_degree_vertex << std::endl;
    std::cout << "Degree of the vertex: " << max_degree << std::endl;

    // Now count the neighbours of that vertex that has less than 1000 neighbours
    int count = 0;
    for (auto neighbor : directed.getNeighbors(max_degree_vertex))
    {
        if (directed.getDegree(neighbor) < 20)
        {
            count++;
        }
    }

    // Print the count of neighbours of the vertex with less than 1000 neighbours
    std::cout << "Number of neighbours of the vertex with less than 20 neighbours: " << count << std::endl;

    
    std::vector<int> traingle_count_arr(directed.getNumVertices(), 0);

    std::cout << "Done Filtering Graph" << std::endl;

    auto start = std::chrono::high_resolution_clock::now();
    countTriangles(directed, traingle_count_arr);
    auto end = std::chrono::high_resolution_clock::now();
    std::chrono::duration<double> elapsed = end - start;

    // reduce vertex counts by summing
    int final_count = 0;
    for (int i = 0; i < directed.getNumVertices(); i++)
    {
        final_count += traingle_count_arr[i];
    }

    // print triangle count of vertex 0
    //std::cout << "Triangle count of vertex 0: " << traingle_count_arr[0] << std::endl;

    std::cout << "Number of triangles: " << final_count << std::endl;
    std::cout << "Time taken: " << elapsed.count() << "s" << std::endl;
}