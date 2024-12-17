#include <iostream>
#include <vector>
#include <set>
#include <utility>
#include <fstream>
#include <sstream>
#include <mutex>
#include <chrono>

class Set
{
private:
    const int capacity = 1023;
    uint32_t *array;
    uint32_t size;

public:
    Set() : array(new uint32_t[1023]), size(0)
    {
    }

    ~Set()
    {
        delete[] array;
    }

    Set(const Set &) = delete;
    Set &operator=(const Set &) = delete;

    Set(Set &&other) noexcept : array(other.array), size(other.size)
    {
        other.array = nullptr;
        other.size = 0;
    }

    Set &operator=(Set &&other) noexcept
    {
        if (this != &other)
        {
            delete[] array;
            array = other.array;
            size = other.size;
            other.array = nullptr;
            other.size = 0;
        }
        return *this;
    }

    void insert(int element)
    {
        for (int i = 0; i < size; i++)
        {
            if (array[i] == element)
                return;
        }

        if (size == capacity)
        {
            std::cerr << "Set is full" << std::endl;
            return;
        }
        array[size] = element;
        size++;
    }

    void unpromising_insert(int element)
    {
        array[size] = element;
        size++;
    }

    int getSize() const
    {
        return size;
    }

    int operator[](int index) const
    {
        if (index >= 0 && index < size)
        {
            return array[index];
        }
        throw std::out_of_range("Index out of range");
    }

    bool empty()
    {
        return size == 0;
    }

    void clear()
    {
        size = 0;
    }

    std::vector<uint32_t> asVector() const {
        std::vector<uint32_t> dat;
        dat.push_back(size);
        for(int i = 0; i < size; i++) {
            dat.push_back(array[i]);
        }
        return dat;
    }
};

class Array
{
private:
    int32_t *array;
    uint32_t size;

public:
    Array(uint32_t size) : array(new int32_t[size]), size(size)
    {
    }

    Array(uint32_t size, int32_t value) : array(new int32_t[size]), size(size)
    {
        for (uint32_t i = 0; i < size; i++)
            array[i] = value;
    }

    Array(const Array &) = delete;
    Array &operator=(const Array &) = delete;

    int32_t &operator[](uint32_t index)
    {
        if (index >= 0 && index < size)
        {
            return array[index];
        }
        throw std::out_of_range("Index out of range");
    }

    uint32_t getSize()
    {
        return size;
    }

    ~Array()
    {
        delete[] array;
    }
};


struct Pair32 {
  uint32_t first;
  uint32_t second;
};

class Graph
{
public:
    Graph() = default;

    Graph(const std::string &filename)
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
        }

        // get the max vertex number from the vertices set and add the vertices from 0 to max
        int max_vertex = *vertices.rbegin();
        for (int i = 0; i <= max_vertex; i++)
        {
            vertices.insert(i);
        }

        file.close();

        adj_list.resize(vertices.size());
        for (const auto &edge : edges)
        {
            adj_list[edge.first].unpromising_insert(edge.second);
            //adj_list[edge.second].unpromising_insert(edge.first);
        }
    }

    void addEdge(int u, int v)
    {
        edges.push_back({(uint32_t)u, (uint32_t)v});
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

    const Set& getNeighbors(int u) const
    {
        return adj_list[u];
    }

    const std::vector<Pair32>& getEdges() {
        return edges;
    }

    const std::vector<Set>& getAdjList() const {
        return adj_list;
    }

private:
    std::vector<Pair32> edges;
    std::set<int> vertices;
    std::vector<Set> adj_list;

    friend void edgeMapParallel(const Graph &g, int vertex, Array &flag_visited, int round, Array &d, Set *set);
};

class Timer {
public:
    Timer() : start_time(std::chrono::high_resolution_clock::now()) {}
    ~Timer() {
        auto end_time = std::chrono::high_resolution_clock::now();
        std::chrono::duration<double> elapsed = end_time - start_time;
        std::cout << "Elapsed time: " << elapsed.count() << " seconds" << std::endl;
    }

private:
    std::chrono::time_point<std::chrono::high_resolution_clock> start_time;
};