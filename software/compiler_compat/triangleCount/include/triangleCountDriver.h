#include <hardCilkDriver.h>
#include "main_defs.h"
#include <fstream>
#include <sstream>
#include <algorithm>
#include <chrono>

void filterGraph(const std::map<uint32_t, std::vector<uint32_t>> &adjMap,
                 std::map<uint32_t, std::vector<uint32_t>> &directed_adjMap) {
  for (const auto &entry : adjMap) {
    uint32_t u = entry.first;
    uint32_t degU = entry.second.size();
    for (uint32_t v : entry.second) {
      uint32_t degV = adjMap.count(v) ? adjMap.at(v).size() : 0;
      if (degU < degV || (degU == degV && u < v))
        directed_adjMap[u].push_back(v);
    }
  }
}

void loadGraphFromEdgeList(const std::string &filePath, uint64_t *&pGraph,
                           uint32_t &vertexCount) {
  // --- Pass 1: Read file and build a flexible graph representation ---

  std::ifstream file(filePath);
  if (!file.is_open()) {
    throw std::runtime_error("Error: Could not open file: " + filePath);
  }

  // Use a map to store adjacency lists. This handles sparse graphs
  // (e.g., vertices 0 and 100 exist, but 1-99 don't) and
  // automatically finds the max vertex ID.
  std::map<uint32_t, std::vector<uint32_t>> adjMap;
  std::string line;
  uint32_t u, v;
  uint32_t maxVertexId = 0;

  while (std::getline(file, line)) {
    // Skip empty lines or comments
    if (line.empty() || line[0] == '#') {
      continue;
    }

    std::istringstream iss(line);
    if (!(iss >> u >> v)) {
      std::cerr << "Warning: Skipping malformed line: " << line << std::endl;
      continue;
    }

    // Add the edge to the adjacency map (undirected)
    adjMap[u].push_back(v);
    adjMap[v].push_back(u);

    // Track the maximum vertex ID
    maxVertexId = std::max(maxVertexId, std::max(u, v));
  }
  file.close();

  // Apply the filter function to the graph to get a directed graph with the
  // filtered edges
  std::map<uint32_t, std::vector<uint32_t>> directed_adjMap;
  filterGraph(adjMap, directed_adjMap);

  // Set the output vertexCount. Assumes 0-based indexing.
  vertexCount = (directed_adjMap.empty()) ? 0 : (maxVertexId + 1);
  if (vertexCount == 0) {
    pGraph = nullptr;
    return;
  }

  // --- Pass 2: Allocate and populate the C-style array structure ---

  // Allocate the main pGraph array: 2 * vertexCount entries.
  // We use `new` as requested for dynamic allocation.
  try {
    pGraph = new uint64_t[2 * vertexCount];
  } catch (const std::bad_alloc &e) {
    throw std::runtime_error("Error: Failed to allocate memory for pGraph.");
  }

  // Initialize to null/zero
  std::fill_n(pGraph, 2 * vertexCount, 0);

  // Iterate through the map and convert to the final format
  for (auto &pair : directed_adjMap) {
    uint32_t vertexId = pair.first;
    std::vector<uint32_t> &neighbors = pair.second;

    // --- Process neighbors as requested ---
    // 1. Sort in ascending order
    std::sort(neighbors.begin(), neighbors.end());

    // 2. (Optional but good practice) Remove duplicate edges
    neighbors.erase(std::unique(neighbors.begin(), neighbors.end()),
                    neighbors.end());

    size_t neighborSize = neighbors.size();

    if (neighborSize == 0) {
      // Already set to (nullptr, 0) by std::fill_n
      continue;
    }

    // --- Allocate and populate the neighbor list ---
    uint32_t *neighborList = nullptr;
    try {
      neighborList = new uint32_t[neighborSize];
    } catch (const std::bad_alloc &e) {
      // Cleanup before re-throwing
      for (uint32_t i = 0; i < vertexId; ++i) {
        delete[] (uint32_t *)pGraph[2 * i];
      }
      delete[] pGraph;
      pGraph = nullptr;
      vertexCount = 0;
      throw std::runtime_error(
          "Error: Failed to allocate memory for neighbor list.");
    }

    std::copy(neighbors.begin(), neighbors.end(), neighborList);

    // --- Store pointer and size in pGraph ---
    // Cast the pointer to uint64_t to store it
    pGraph[2 * vertexId] = (uint64_t)neighborList;
    // Store the size
    pGraph[2 * vertexId + 1] = (uint64_t)neighborSize;
  }
}

void freeGraph(uint64_t *pGraph, uint32_t vertexCount) {
  if (pGraph == nullptr) {
    return;
  }

  // Loop through and delete each individual neighbor list
  for (uint32_t v = 0; v < vertexCount; ++v) {
    // Get the pointer from the graph structure
    uint32_t *neighborList = (uint32_t *)pGraph[2 * v];
    if (neighborList != nullptr) {
      delete[] neighborList;
    }
  }

  // Finally, delete the main pGraph array itself
  delete[] pGraph;
}

bool condition(int32_t val)
{
    return val == 1;
}


// A function that takes a graph pointer and returns an initilaized pointer to the graph on the FPGA.
uint64_t initializeGraphOnFPGA(Memory *memory, uint64_t *pGraph, uint32_t vertexCount) {
    // Deep copy the graph structure to the FPGA. This involves copying each neighbor list and then the main pGraph array with updated pointers.
    std::vector<uint64_t> fpgaPGraph(2 * vertexCount);

    for (uint32_t v = 0; v < vertexCount; v++) {
        uint32_t *neighborList = reinterpret_cast<uint32_t *>(pGraph[2 * v]);
        uint64_t degree = pGraph[2 * v + 1];

        if (degree == 0 || neighborList == nullptr) {
            fpgaPGraph[2 * v] = 0;
            fpgaPGraph[2 * v + 1] = 0;
        } else {
            uint64_t neighborList_addr = memory->allocateMemFPGA(sizeof(uint32_t) * degree, sizeof(uint32_t));
            memory->copyToDevice(neighborList_addr, reinterpret_cast<const uint8_t *>(neighborList), sizeof(uint32_t) * degree);
            fpgaPGraph[2 * v] = neighborList_addr;
            fpgaPGraph[2 * v + 1] = degree;
        }
    }

    uint64_t pGraph_addr = memory->allocateMemFPGA(sizeof(uint64_t) * 2 * vertexCount, sizeof(uint64_t));
    memory->copyToDevice(pGraph_addr, reinterpret_cast<const uint8_t *>(fpgaPGraph.data()), sizeof(uint64_t) * 2 * vertexCount);

    // // Update CPU pGraph with FPGA addresses so callers see the FPGA layout.
    // std::copy(fpgaPGraph.begin(), fpgaPGraph.end(), pGraph);

    return pGraph_addr;
}

class triangleCountDriver : public hardCilkDriver {
    std::string graphPath_;
public:
    triangleCountDriver(Memory *memory, const std::string &graphPath)
        : hardCilkDriver(memory), graphPath_(graphPath) {}

    int run_test_bench() override {
        std::cout << "Starting triangle count test bench..." << std::endl;

        spawnerFunction_task root_task_0 = {};
        int counter = 2;

        spawnerFunction_cont0_task cont0_task = {};
        uint64_t addr = memory_->allocateMemFPGA(sizeof(spawnerFunction_cont0_task), sizeof(spawnerFunction_cont0_task));
        memory_->copyToDevice(addr, reinterpret_cast<const uint8_t *>(&cont0_task), sizeof(cont0_task));
        memory_->copyToDevice(addr, reinterpret_cast<const uint8_t *>(&counter), sizeof(counter));

        std::cout << "Counter initialized to: " << counter << std::endl;

        uint64_t *pGraph;
        uint32_t vertexCount;

        loadGraphFromEdgeList(graphPath_, pGraph, vertexCount);
        uint32_t *triangles = new uint32_t[vertexCount];
        for (uint32_t i = 0; i < vertexCount; i = i + 1) {
            triangles[i] = 0;
        }

  
        uint64_t pGraph_addr = initializeGraphOnFPGA(memory_, pGraph, vertexCount);

        // For debugging: log pGraph_addr and first 20 entries stating address and degree
        std::cout << "pGraph_addr: " << std::hex << pGraph_addr << std::dec << std::endl;
        for (uint32_t i = 0; i < std::min(vertexCount, static_cast<uint32_t>(20)); i++) {
            uint64_t neighborListAddr = pGraph[2 * i];
            uint64_t degree = pGraph[2 * i + 1];
            std::cout << "Vertex " << i << ": neighborListAddr = " << std::hex << neighborListAddr << std::dec << ", degree = " << degree << std::endl;
        }
        
        uint64_t triangles_addr = memory_->allocateMemFPGA(sizeof(uint32_t) * vertexCount, 512);
        memory_->copyToDevice(triangles_addr, reinterpret_cast<const uint8_t *>(triangles), sizeof(uint32_t) * vertexCount);

        root_task_0.cont = addr;
        root_task_0.vertexCount = vertexCount;
        root_task_0.pGraph = pGraph_addr;
        root_task_0.triangleCounts = triangles_addr;
        memset(root_task_0._padding, 0, sizeof(root_task_0._padding));


        std::vector<spawnerFunction_task> base_task_data = {root_task_0};

        initSystem(base_task_data, &condition);

        startSystem();

        auto start_management = std::chrono::high_resolution_clock::now();
        managementLoop();
        auto end_management = std::chrono::high_resolution_clock::now();
        std::chrono::duration<double> management_duration = end_management - start_management;
        std::cout << "Time taken by management_loop: " << management_duration.count() << " seconds" << std::endl;

        memory_->copyFromDevice(reinterpret_cast<uint8_t *>(pGraph), pGraph_addr, sizeof(uint64_t) * 2 * vertexCount);
        memory_->copyFromDevice(reinterpret_cast<uint8_t *>(triangles), triangles_addr, sizeof(uint32_t) * vertexCount);

        uint64_t totalTriangles = 0;
        for (uint32_t i = 0; i < vertexCount; i = i + 1) {
            totalTriangles += triangles[i];
        }
        std::cout << "Total number of triangles: " << totalTriangles << std::endl;
        // delete[] triangles;
        // freeGraph(pGraph, vertexCount);

        return 0;
    }
};


