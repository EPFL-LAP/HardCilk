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
#include <float.h>
#include <numeric>


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



#define epsilon (1e-3)
#define damping 0.85

inline double mapFn(Graph &g, std::vector<double> &Pcurr, int32_t u)
{
  return Pcurr[u] / g.getDegree(u); //??
}

inline double getContributions(Graph &g, std::vector<double> &Pcurr, int32_t u)
{
  double sum = 0;
  for(auto v : g.getNeighbors(u))
  {
    sum += mapFn(g, Pcurr, v);
  }
  return sum;
}

void applyFn(Graph &g, std::vector<double> &Pcurr, std::vector<double> &Pnext, std::vector<double> &diffs, int32_t u)
{
  Pnext[u] = (1 - damping) / g.getNumVertices() + damping * getContributions(g, Pcurr, u);
  diffs[u] = std::abs(Pnext[u] - Pcurr[u]);
}

inline double reduceDiffs(std::vector<double> &diffs)
{
  //double sum = std::reduce(diffs.begin(), diffs.end(), 0.0);
  
  // Do the accumulation in a loop
    double sum = 0;
    for (int i = 0; i < diffs.size(); i++)
    {
        sum += diffs[i];
        if(sum > epsilon)
        {
            break;
        }
    }
  

  return sum;
}

int iterations_count = 0;

void pageRank(Graph &g, std::vector<double> &Pcurr, std::vector<double> &Pnext, std::vector<double> &diffs)
{
    double error = DBL_MAX;
    std::vector<double> * Pcurr_ptr = &Pcurr;
    std::vector<double> * Pnext_ptr = &Pnext;
    while(error > epsilon){
        //iterations_count+=1;
        //cilk::reducer_opadd<double> sum(0.0);
        for(int i = 0; i < g.getNumVertices(); i++)
        {
            cilk_spawn applyFn(g, *Pcurr_ptr, *Pnext_ptr, diffs, i);
        }
        cilk_sync;
        error = reduceDiffs(diffs);
        std::swap(Pcurr_ptr, Pnext_ptr);
    }
}



int main()
{
    //Graph g("/home/shahawy/graphs/single_triangle.txt", false);
    //Graph g("/home/shahawy/email-EuAll.txt", false);
    //Graph g("/home/shahawy/graphs/cit-HepPh.txt", false);
    Graph g("/home/shahawy/graphs/soc-LiveJournal1.txt", false);
    //Graph g("/home/shahawy/congress_network/congress.edgelist", false);
    //Graph g("/home/shahawy/email-EuAll_r.txt", false);
    //Graph g("/home/shahawy/p2p-Gnutella31.txt", false);
    //Graph g ("/home/shahawy/graphs/twitter_combined.txt");
    //Graph g ("/home/shahawy/opencilk_ws/benchmarks/triangle/example_graph.txt", false);
    //Graph g("/home/shahawy/graphs/facebook_large/musae_facebook_edges.csv", false);
    //Graph g("/home/shahawy/graphs/email-EuAll_r.txt", false);
    std::cout << "Done Reading Graph" << std::endl;

    
    std::vector<double> Pcurr (g.getNumVertices(), 1.0 / g.getNumVertices());
    std::vector<double> Pnext (g.getNumVertices(), 0);
    std::vector<double> diffs (g.getNumVertices(), 0);



    auto start = std::chrono::high_resolution_clock::now();
    pageRank(g, Pcurr, Pnext, diffs);
    auto end = std::chrono::high_resolution_clock::now();
    std::chrono::duration<double> elapsed = end - start;

    // Print the top 10 vertices with the highest page rank in Pcurr
    std::vector<std::pair<int, double>> page_rank;
    for (int i = 0; i < g.getNumVertices(); i++)
    {
        page_rank.push_back({i, Pcurr[i]});
    }

    std::sort(page_rank.begin(), page_rank.end(), [](std::pair<int, double> a, std::pair<int, double> b) {
        return a.second > b.second;
    });

    for (int i = 0; i < 10 && i < g.getNumVertices(); i++)
    {
        std::cout << page_rank[i].first << " " << page_rank[i].second << std::endl;
    }

    std::cout << "Elapsed Time: " << elapsed.count() << "s" << std::endl;
    std::cout << "Iterations: " << iterations_count << std::endl;
}