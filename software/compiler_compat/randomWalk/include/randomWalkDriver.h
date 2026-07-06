#pragma once

#include <hardCilkDriver.h>
#include "randomWalk_defs.h"
#include <fstream>
#include <sstream>
#include <map>
#include <vector>
#include <algorithm>
#include <chrono>
#include <iostream>
#include <power_monitor.h>

void loadGraphFromEdgeList(const std::string &filePath, uint64_t *&pGraph,
                           uint32_t &vertexCount) {
  std::ifstream file(filePath);
  if (!file.is_open()) {
    throw std::runtime_error("Error: Could not open file: " + filePath);
  }

  std::map<uint32_t, std::vector<uint32_t>> adjMap;
  std::string line;
  uint32_t u, v;
  uint32_t maxVertexId = 0;

  while (std::getline(file, line)) {
    if (line.empty() || line[0] == '#') {
      continue;
    }

    std::istringstream iss(line);
    if (!(iss >> u >> v)) {
      std::cerr << "Warning: Skipping malformed line: " << line << std::endl;
      continue;
    }

    adjMap[u].push_back(v);
    adjMap[v].push_back(u);

    maxVertexId = std::max(maxVertexId, std::max(u, v));
  }
  file.close();

  vertexCount = (adjMap.empty()) ? 0 : (maxVertexId + 1);
  if (vertexCount == 0) {
    pGraph = nullptr;
    return;
  }

  try {
    pGraph = new uint64_t[2 * vertexCount];
  } catch (const std::bad_alloc &e) {
    throw std::runtime_error("Error: Failed to allocate memory for pGraph.");
  }

  std::fill_n(pGraph, 2 * vertexCount, 0);

  for (auto &pair : adjMap) {
    uint32_t vertexId = pair.first;
    std::vector<uint32_t> &neighbors = pair.second;

    std::sort(neighbors.begin(), neighbors.end());
    neighbors.erase(std::unique(neighbors.begin(), neighbors.end()),
                    neighbors.end());

    size_t neighborSize = neighbors.size();
    if (neighborSize == 0) continue;

    uint32_t *neighborList = nullptr;
    try {
      neighborList = new uint32_t[neighborSize];
    } catch (const std::bad_alloc &e) {
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
    pGraph[2 * vertexId] = (uint64_t)neighborList;
    pGraph[2 * vertexId + 1] = (uint64_t)neighborSize;
  }
}

void freeGraph(uint64_t *pGraph, uint32_t vertexCount) {
  if (pGraph == nullptr) return;

  for (uint32_t v = 0; v < vertexCount; ++v) {
    uint32_t *neighborList = (uint32_t *)pGraph[2 * v];
    if (neighborList != nullptr) delete[] neighborList;
  }
  delete[] pGraph;
}

bool condition(int32_t val) {
    return val == 1;
}

uint64_t initializeGraphOnFPGA(Memory *memory, uint64_t *pGraph, uint32_t vertexCount) {
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
    return pGraph_addr;
}

class randomWalkDriver : public hardCilkDriver {
    std::string graphPath_;
public:
    randomWalkDriver(Memory *memory, const std::string &graphPath)
        : hardCilkDriver(memory), graphPath_(graphPath) {}

    int run_test_bench() override {
        std::cout << "Starting random walk test bench..." << std::endl;

        oneWalkPerNode_cont0_task cont0_task = {};
        int counter = 2;

        uint64_t addr = memory_->allocateMemFPGA(sizeof(oneWalkPerNode_cont0_task), sizeof(oneWalkPerNode_cont0_task));
        memory_->copyToDevice(addr, reinterpret_cast<const uint8_t *>(&cont0_task), sizeof(cont0_task));
        memory_->copyToDevice(addr, reinterpret_cast<const uint8_t *>(&counter), sizeof(counter));

        // log return address
        std::cout << "Return address for continuation: " << std::hex << addr << std::dec << std::endl;

        uint64_t *pGraph;
        uint32_t vertexCount;

        loadGraphFromEdgeList(graphPath_, pGraph, vertexCount);

        uint32_t walkLength = 32;
        float stopProb = 0.15f;
        uint32_t stop_thresh = (uint32_t)(stopProb * (double)UINT32_MAX);

        int *global_buffer = new int[vertexCount * walkLength];
        std::fill_n(global_buffer, vertexCount * walkLength, -1);

        uint64_t pGraph_addr = initializeGraphOnFPGA(memory_, pGraph, vertexCount);

        // log graph address
        std::cout << "Graph address on FPGA: " << std::hex << pGraph_addr << std::dec << std::endl;

        uint64_t global_buffer_addr = memory_->allocateMemFPGA(sizeof(int) * vertexCount * walkLength, 512);
        memory_->copyToDevice(global_buffer_addr, reinterpret_cast<const uint8_t *>(global_buffer), sizeof(int) * vertexCount * walkLength);

        // log global buffer address
        std::cout << "Global buffer address on FPGA: " << std::hex << global_buffer_addr << std::dec << std::endl;

        oneWalkPerNode_task root_task_0 = {};
        root_task_0.cont = addr;
        root_task_0.vertexCount = vertexCount;
        root_task_0.pGraph = pGraph_addr;
        root_task_0.global_buffer = global_buffer_addr;
        root_task_0.stop_thresh = stop_thresh;
        root_task_0.walkLength = walkLength;
        memset(root_task_0._padding, 0, sizeof(root_task_0._padding));

        std::vector<oneWalkPerNode_task> base_task_data = {root_task_0};
        initSystem(base_task_data, &condition);

        PowerMonitor mon(0, /*interval_ms=*/100);   // device index 0 = 0000:01:00.1

        double idle_watts = mon.readPowerWatts();
        std::cout << "Idle power: " << idle_watts << " W" << std::endl;

        startSystem();

        mon.start();

        auto start_management = std::chrono::high_resolution_clock::now();
        managementLoop();
        auto end_management = std::chrono::high_resolution_clock::now();

        auto r = mon.stop();

        std::chrono::duration<double> management_duration = end_management - start_management;
        std::cout << "Time taken by management_loop: " << management_duration.count() << " seconds" << std::endl;

        std::cout << "Energy: " << r.energy_joules << " J"
                  << "  (avg " << r.avg_power_watts << " W over "
                  << r.duration_seconds << " s, " << r.num_samples << " samples)" << std::endl;
        std::cout << "Dynamic energy (idle-subtracted): "
                  << r.energy_joules - idle_watts * r.duration_seconds << " J" << std::endl;

        memory_->copyFromDevice(reinterpret_cast<uint8_t *>(global_buffer), global_buffer_addr, sizeof(int) * vertexCount * walkLength);

        for (uint32_t u = 0; u < std::min(vertexCount, 5u); u++) {
            std::cout << "Walk for vertex " << u << ": ";
            for (uint32_t step = 0; step < walkLength; step++) {
                std::cout << global_buffer[u * walkLength + step] << " ";
            }
            std::cout << std::endl;
        }

        freeGraph(pGraph, vertexCount);
        return 0;
    }
};
