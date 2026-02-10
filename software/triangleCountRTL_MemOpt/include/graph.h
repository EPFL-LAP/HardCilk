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

#include <type_traits>
#include <stdint.h>
#include <fstream>
#include <vector>

namespace janberq {

/**
 * Aligns a given number, returns the offset added.
 */
template<typename T>
void align(std::type_identity_t<T> alignment, T& t, T& offset) {
    auto mask = (alignment - 1);
    if ((t & mask) == 0) {
        offset = 0;
        return;
    }

    auto aligned = (t | mask) + 1;
    offset = aligned - t;
    t = aligned;
}

template<typename T>
void align(std::type_identity_t<T> alignmentSmall, std::type_identity_t<T> alignmentBig, T& t, T size, T& offset) {
    auto maskSmall = (alignmentSmall - 1);
    auto maskBig = (alignmentBig - 1);

    if ((t & maskBig) == 0) {
        offset = 0;
        return;
    }

    T offset1 = 0, offset2 = 0;
    align(alignmentSmall, t, offset1);

    if (((t + size - 1) & ~maskBig) != (t & ~maskBig))
        align(alignmentBig, t, offset2);

    offset = offset1 + offset2;
}

struct Graph {
    Graph(uint32_t nodeCount)
        : adjLists_(nodeCount) {
    }

    auto count() const noexcept {
        return adjLists_.size();
    }

    void connect(uint32_t vertexA, uint32_t vertexB) noexcept {
        assert(vertexA < count());
        assert(vertexB < count());

        adjLists_[vertexA].push_back(vertexB);
    }

    auto const& nodes() const noexcept {
        return adjLists_;
    }

    auto const& node(uint32_t vertex) const noexcept {
        assert(vertex < count());
        return adjLists_[vertex];
    }

    uint32_t countNonEmpty() const noexcept {
        uint32_t result = 0;

        for (auto const &node: adjLists_) {
            if (node.size() > 0)
                result++;
        }

        return result;
    }

    static Graph loadFile(const char* fpath, bool directed = true) {
        // this is the worst ever way to load a file
        struct Edge {
            uint32_t a, b;
        };
        std::vector<Edge> edges;

        int64_t max = -1;

        {
            std::ifstream ifs { fpath };

            Edge edge;
            while (ifs >> edge.a >> edge.b) {
                edges.push_back(edge);

                if (!directed) {
                    Edge edgeReversed { edge.b, edge.a };
                    edges.push_back(edgeReversed);
                }

                max = std::max((int64_t)std::max(edge.a, edge.b), max);
            }
        }

        Graph g { static_cast<uint32_t>(max + 1) };
        for (auto const& edge : edges) {
            g.connect(edge.a, edge.b);
        }

        g.normalize();

        return g;
    }

    void normalize() {
        for (auto& adjList : adjLists_) {
            std::set<std::uint32_t> set(adjList.begin(), adjList.end());
            adjList.assign(set.begin(), set.end());
        }
    }

    void writeDirect(uint8_t* mem, uint64_t base, uint64_t& ptrGraph, uint64_t& ptrAdjList) {
        std::vector<std::pair<uint64_t, uint64_t>> graph;
        uint64_t offset;

        ptrAdjList = base;

        align(4, ptrAdjList, offset);
        base += offset;
        mem += offset;

        for (auto const& adjList : adjLists_) {
            auto sz = adjList.size() * sizeof(adjList[0]);

            // align(2048, 1ull << 14, base, sz, offset);
            align(64, 1ull << 14, base, sz, offset);
            
            // align(4, 128, base, sz, offset);
            // align(4, 256ull << 20, base, sz, offset);
            mem += offset;
            // base is already updated

            std::memcpy(mem, adjList.data(), sz);
            graph.push_back({ base, adjList.size() });

            mem += sz;
            base += sz;
        }

        ptrGraph = base;

        align(16, ptrGraph, offset);
        base += offset;
        mem += offset;

        auto sz = graph.size() * sizeof(graph[0]);
        std::memcpy(mem, graph.data(), sz);
    }

private:
    std::vector<std::vector<uint32_t>> adjLists_;
};


bool filterEdge(Graph const& g, uint32_t vertexA, uint32_t vertexB) {
    uint32_t degreeA = g.node(vertexA).size();
    uint32_t degreeB = g.node(vertexB).size();
    return (degreeA > degreeB || (degreeA == degreeB && vertexA > vertexB));
}

template<typename Filter>
Graph filterGraph(Graph const& g, Filter filter) {
    struct Edge {
        uint32_t a, b;
    };
    std::vector<Edge> edges;
    int64_t max = -1;

    for (uint32_t i = 0; i < g.count(); i++) {
        for (auto neighbor : g.node(i)) {
            if (filter(g, i, neighbor)) {
                edges.emplace_back(Edge { neighbor, i });
                max = std::max((int64_t)std::max(neighbor, i), max);
            }
        }
    }

    Graph result { static_cast<uint32_t>(max + 1) };
    for (auto const& edge : edges) {
        result.connect(edge.a, edge.b);
    }

    result.normalize();

    return result;
}

}
