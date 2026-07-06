#include <algorithm>
#include <chrono>
#include <fstream>
#include <hardCilkDriver.h>
#include <pageRank_defs.h>
#include <sstream>
#include <float.h>


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

    // Add edges for an undirected graph
    adjMap[u].push_back(v);
    adjMap[v].push_back(u);

    // Track the maximum vertex ID
    maxVertexId = std::max({maxVertexId, u, v});
  }
  file.close();

  // Set the output vertexCount. Assumes 0-based indexing.
  vertexCount = (adjMap.empty()) ? 0 : (maxVertexId + 1);
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
  for (auto &pair : adjMap) {
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

bool condition(int32_t val) { return val == 1; }

// A function that takes a graph pointer and returns an initilaized pointer to
// the graph on the FPGA.
uint64_t initializeGraphOnFPGA(Memory *memory, uint64_t *pGraph,
                               uint32_t vertexCount) {
  // Deep copy the graph structure to the FPGA. This involves copying each
  // neighbor list and then the main pGraph array with updated pointers.
  std::vector<uint64_t> fpgaPGraph(2 * vertexCount);

  for (uint32_t v = 0; v < vertexCount; v++) {
    uint32_t *neighborList = reinterpret_cast<uint32_t *>(pGraph[2 * v]);
    uint64_t degree = pGraph[2 * v + 1];

    if (degree == 0 || neighborList == nullptr) {
      fpgaPGraph[2 * v] = 0;
      fpgaPGraph[2 * v + 1] = 0;  
      std::cout << "Warning, the current impleemntation does not accept zero neighbours in the graph!" << std::endl;
      std::cout << "Vertex: " << v << " has degree: " << degree << std::endl;
    } else {
      uint64_t neighborList_addr =
          memory->allocateMemFPGA(sizeof(uint32_t) * degree, sizeof(uint32_t));
      memory->copyToDevice(neighborList_addr,
                           reinterpret_cast<const uint8_t *>(neighborList),
                           sizeof(uint32_t) * degree);
      fpgaPGraph[2 * v] = neighborList_addr;
      fpgaPGraph[2 * v + 1] = degree;
    }
  }

  uint64_t pGraph_addr = memory->allocateMemFPGA(
      sizeof(uint64_t) * 2 * vertexCount, sizeof(uint64_t));
  memory->copyToDevice(pGraph_addr,
                       reinterpret_cast<const uint8_t *>(fpgaPGraph.data()),
                       sizeof(uint64_t) * 2 * vertexCount);

  // // Update CPU pGraph with FPGA addresses so callers see the FPGA layout.
  // std::copy(fpgaPGraph.begin(), fpgaPGraph.end(), pGraph);

  return pGraph_addr;
}

class pageRankDriver : public hardCilkDriver {
  std::string graphPath_;

public:
  pageRankDriver(Memory *memory, const std::string &graphPath)
      : hardCilkDriver(memory), graphPath_(graphPath) {}

  int run_test_bench() override {
    std::cout << "Starting pageRank count test bench..." << std::endl;

    spawnerFunction_task root_task_0 = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    int counter = 2;

    uint64_t addr =
        memory_->allocateMemFPGA(sizeof(root_task_0), sizeof(root_task_0));
    memory_->copyToDevice(addr, reinterpret_cast<const uint8_t *>(&root_task_0),
                          sizeof(root_task_0));
    memory_->copyToDevice(addr, reinterpret_cast<const uint8_t *>(&counter),
                          sizeof(counter));

    uint64_t *pGraph;
    uint32_t vertexCount;
    float *pPrCurr;
    float *pPrNext;
    float *diffs;
    uint32_t iterationCount;

    std::cout << "Counter initialized to: " << counter << std::endl;

    loadGraphFromEdgeList(graphPath_, pGraph, vertexCount);
    pPrCurr = new float[vertexCount];
    pPrNext = new float[vertexCount];
    diffs = new float[vertexCount];
    for (uint32_t i = 0; i < vertexCount; i = i + 1) {
      pPrCurr[i] = 1.0 / vertexCount; // Initialize the pagerank current values
                                      // as 1 / graph vertex count
      pPrNext[i] = 0;
      diffs[i] = 0;
    }

    uint64_t pGraph_addr = initializeGraphOnFPGA(memory_, pGraph, vertexCount);


    float error = DBL_MAX;
    uint32_t current_iteration = 0;
    iterationCount = 1;

    uint64_t pPrCurr_addr =
        memory_->allocateMemFPGA(sizeof(float) * vertexCount, 512);
    memory_->copyToDevice(pPrCurr_addr,
                          reinterpret_cast<const uint8_t *>(pPrCurr),
                          sizeof(float) * vertexCount);
    uint64_t pPrNext_addr =
        memory_->allocateMemFPGA(sizeof(float) * vertexCount, 512);
    memory_->copyToDevice(pPrNext_addr,
                          reinterpret_cast<const uint8_t *>(pPrNext),
                          sizeof(float) * vertexCount);
    uint64_t diffs_addr =
        memory_->allocateMemFPGA(sizeof(float) * vertexCount, 512);
    memory_->copyToDevice(diffs_addr, reinterpret_cast<const uint8_t *>(diffs),
                          sizeof(float) * vertexCount);

    root_task_0.cont = addr;
    root_task_0.iterationCount = iterationCount;
    root_task_0.vertexCount = vertexCount;
    root_task_0.pGraph = pGraph_addr;
    root_task_0.pPrCurr = pPrCurr_addr;
    root_task_0.pPrNext = pPrNext_addr;
    root_task_0.diffs = diffs_addr;
    root_task_0.error = error;
    root_task_0.current_iteration = current_iteration;
    //root_task_0._padding = 0;

    std::vector<spawnerFunction_task> base_task_data = {root_task_0};
    initSystem(base_task_data, &condition);


    startSystem();

    auto start_management = std::chrono::high_resolution_clock::now();
    managementLoop();
    auto end_management = std::chrono::high_resolution_clock::now();
    std::chrono::duration<double> management_duration =
        end_management - start_management;
    std::cout << "Time taken by management_loop: "
              << management_duration.count() << " seconds" << std::endl;

    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(pGraph), pGraph_addr,
                            sizeof(uint64_t) * 2 * vertexCount);
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(pPrCurr), pPrCurr_addr,
                            sizeof(float) * vertexCount);
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(pPrNext), pPrNext_addr,
                            sizeof(float) * vertexCount);
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(diffs), diffs_addr,
                            sizeof(float) * vertexCount);

    if(iterationCount == 1){
      std::swap(pPrCurr, pPrNext);
    } 

    std::vector<std::pair<float, int>> ranks_sorted_desc;
    for (uint32_t i = 0; i < vertexCount; i = i + 1) {
      ranks_sorted_desc.push_back(std::make_pair(pPrCurr[i], i));
    }
    std::sort(ranks_sorted_desc.begin(), ranks_sorted_desc.end());
    std::reverse(ranks_sorted_desc.begin(), ranks_sorted_desc.end());
    for (uint32_t i = 0; i < 10 && i < vertexCount; i = i + 1) {
      std::cout << "Index: " << ranks_sorted_desc[i].second
                << " Rank: " << ranks_sorted_desc[i].first << std::endl;
    }

    return 0;
  }
};
