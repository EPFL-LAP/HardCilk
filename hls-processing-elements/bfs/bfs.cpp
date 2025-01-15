#include "descriptors.h"
#include <cstddef>
#include <cstdint>
#include <etc/autopilot_ssdm_op.h>
#include <string.h>
#include <stdio.h>
#include <cmath>

#define N_LOCAL_SETS 16

void set_insert(void *mem, Addr set, uint32_t val) {
#pragma HLS INTERFACE mode = m_axi port = mem
  uint32_t size = MEM_ARR_IN(mem, set, 0, uint32_t);
  for (size_t i = 0; i < size; i++) {
    if (MEM_ARR_IN(mem, set, i + 1, uint32_t) == val)
      return;
  }
  MEM_ARR_OUT(mem, set, size + 1, uint32_t, val);
  MEM_ARR_OUT(mem, set, 0, uint32_t, size + 1);
}

void set_insert(void *mem, Addr set, uint32_t val, uint32_t &size) {
//#pragma HLS INTERFACE mode = m_axi port = mem
  const int help_pipeline_size = 8;
  const int shift_val = 3;

  bool help_pipeline[help_pipeline_size];
  memset((void *) help_pipeline, 0, sizeof(help_pipeline));
//   for(int i = 0; i < help_pipeline_size; i++){
//       help_pipeline[i] = false;
//   }

  int globalLoopSize = (size/help_pipeline_size)+1;

  for(int j = 0; j < globalLoopSize; j++){
      for (size_t i = j*help_pipeline_size; (i < size) && (i < ((j+1) << shift_val)); i++) {
        help_pipeline[i%help_pipeline_size] = (MEM_ARR_IN(mem, set, i + 1, uint32_t) == val);
      }
      for (size_t i = j*help_pipeline_size; (i < size) && (i < ((j+1) << shift_val)); i++) {
        if(help_pipeline[i%help_pipeline_size] == true)
            return;
      }
  }

//   for (size_t i = 0; i < size; i++) {
//     help_pipeline[i%help_pipeline_size] = (MEM_ARR_IN(mem, set, i + 1, uint32_t) == val);
//     if(help_pipeline[i%help_pipeline_size] == true)
//         return;
//   }

  size++; // Why are we incrementing before insertion? ai'nt this the address we should write at first?
  MEM_ARR_OUT(mem, set, size, uint32_t, val);
}

bool cond(void *mem, Addr flag_visited, int vertex) {
#pragma HLS INTERFACE mode = m_axi port = mem
  return MEM_ARR_IN(mem, flag_visited, vertex, uint8_t) == 0;
}

bool test_and_set(void *mem, Addr flag_visited, int vertex) {
#pragma HLS INTERFACE mode = m_axi port = mem
  // std::mutex mtx;
  // std::lock_guard<std::mutex> lock(mtx);
  if (MEM_ARR_IN(mem, flag_visited, vertex, uint8_t) == 0) {
    // flag_visited[vertex] = 1;
    MEM_ARR_OUT(mem, flag_visited, vertex, uint8_t, 1);
    return true;
  }
  return false;
}

bool update(void *mem, Addr flag_visited, uint32_t neighbor, uint32_t d,
            uint16_t round) {
#pragma HLS INTERFACE mode = m_axi port = mem
  if (!MEM_ARR_IN(mem, flag_visited, neighbor, uint8_t) &&
      test_and_set(mem, flag_visited, neighbor)) {
    // d[neighbor] = round;
    MEM_ARR_OUT(mem, d, neighbor, uint16_t, round);
    return true;
  }
  return false;
}

uint64_t edgeMapParallelPart1(void *mem, hls::stream<task_args> &taskIn,
                              volatile uint32_t &loop_done, uint32_t &gSize) {
  // The arguments
  auto task = taskIn.read();
  uint32_t vertex = task.vertex;
  uint16_t round = task.round;
  Addr g_edges = task.g_edges;
  Addr flag_visited = task.flag_visited;
  Addr d = task.d;
  Addr set = task.set;
  uint64_t cont = task.cont;

  // TODO: THIS IS A DAE, HOW TO DO IT IN HLS?
  Addr neighbors = MEM_ARR_IN(mem, g_edges, vertex, Addr);
  uint32_t size_neighbors = MEM_ARR_IN(mem, neighbors, 0, uint32_t);
  uint32_t set_size = 0;
  for (uint32_t i = 0; i < size_neighbors; i++) {
    // auto edge = g.edges[i];
    uint32_t neighbor = MEM_ARR_IN(mem, neighbors, i + 1, uint32_t);

    if (update(mem, flag_visited, neighbor, d, round)) {
        set_size++;
        MEM_ARR_OUT(mem, set, set_size, uint32_t, neighbor);
    }
    loop_done += 1;
  }
  MEM_ARR_OUT(mem, set, 0, uint32_t, set_size);
  loop_done += 1;
  return cont;
}

void edgeMapParallelPart2(void *mem, hls::stream<uint64_t> &argOut,
                          uint64_t cont, volatile uint32_t &loop_done,
                          uint32_t &gSize) {

  // We should not depend on gSize here anymore as it is meaningless compared to the previous function.
  // I am not sure even why is it still working :'D
  while (loop_done < gSize) {
  }
  argOut.write(cont);
}

void edgeMapParallel(void *mem, hls::stream<task_args> &taskIn,
                     hls::stream<uint64_t> &argOut) {
#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = argOut
#pragma HLS INTERFACE mode = m_axi port = mem
#pragma HLS PIPELINE off

  volatile uint32_t loop_done = 0;
  uint32_t gSize;

  auto cont = edgeMapParallelPart1(mem, taskIn, loop_done, gSize);
  edgeMapParallelPart2(mem, argOut, cont, loop_done, gSize);
}

void edgeMap(void *mem,
             void *mem_2,
             hls::stream<uint64_t> &mallocIn,
             hls::stream<edgemap_args> &taskIn,
             hls::stream<task_args> &taskOutGlobal,
             hls::stream<edgeMap_spawn_next> &spawnNext,
             hls::stream<uint64_t> &closureIn, hls::stream<uint64_t> &argOut) {
#pragma HLS INTERFACE mode = axis port = mallocIn
#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = taskOutGlobal
#pragma HLS INTERFACE mode = axis port = spawnNext
#pragma HLS INTERFACE mode = axis port = closureIn
#pragma HLS INTERFACE mode = axis port = argOut
#pragma HLS INTERFACE mode = m_axi port = mem bundle=gmem
#pragma HLS INTERFACE mode = m_axi port = mem_2 bundle=gmem

#pragma HLS PIPELINE off

  auto task = taskIn.read();
  Addr g_edges = task.g_edges;
  Addr f = task.f;
  Addr flag_visited = task.flag_visited;
  Addr d = task.d;
  Addr sets = task.sets;
  uint32_t g_size = task.g_size;
  uint16_t round = task.round;
  uint32_t size = task.size;
  uint8_t is_sync = task.is_sync;

  volatile uint32_t memoryDependencyVar1 = 0;

  if (is_sync) {
    // f.clear();
    Addr local_sets[N_LOCAL_SETS];
    uint32_t local_sets_sizes[N_LOCAL_SETS];

    const size_t chunk_size = 16L * 1024 * 1024;
    const size_t total_memory = 16L * 1024 * 1024 * 1024;

    for (int i = 0; i < N_LOCAL_SETS; i++) {
      local_sets[i] = total_memory - (i + 1) * chunk_size;
      local_sets_sizes[i] = 0;
    }


    for (int i = 0; i < size; i++) {
      Addr set_i = MEM_ARR_IN(mem_2, sets, i, Addr);  // 2286D000

      uint32_t set_i_size = MEM_ARR_IN(mem_2, set_i, 0, uint32_t); // 228B9000

      printf("Set index: %d, at address: %lu, set size: %d.\n", i, set_i, set_i_size);

      for (int j = 0; j < set_i_size; j++) {
        // f.insert((*sets[i])[j]);
        uint32_t set_i_j = MEM_ARR_IN(mem_2, set_i, j + 1, uint32_t); // 228B9000
        int idx = set_i_j % N_LOCAL_SETS;
        set_insert(mem, local_sets[idx], set_i_j, local_sets_sizes[idx]);
      }
      memoryDependencyVar1++;
    }

    uint32_t cum_sizes[N_LOCAL_SETS];
    cum_sizes[0] = local_sets_sizes[0];


    for (int j = 0; j < local_sets_sizes[0]; j++) {
      uint32_t val = MEM_ARR_IN(mem, local_sets[0], j + 1, uint32_t);
      MEM_ARR_OUT(mem_2, f, j + 1, uint32_t, val);
    }
    for (int i = 1; i < N_LOCAL_SETS; i++) {
      cum_sizes[i] = cum_sizes[i - 1] + local_sets_sizes[i];
      for (int j = 0; j < local_sets_sizes[i]; j++) {
        uint32_t val = MEM_ARR_IN(mem, local_sets[i], j + 1, uint32_t);
        MEM_ARR_OUT(mem_2, f, cum_sizes[i - 1] + j + 1, uint32_t, val); // This guy should not be + 1 every time, does not make sense, its like having gaps in the set
      }
    }
    uint32_t f_size = cum_sizes[N_LOCAL_SETS - 1];
    MEM_ARR_OUT(mem, f, 0, uint32_t, f_size);


    while (memoryDependencyVar1 < size) {
    }
    if (f_size == 0) {
      argOut.write(task.cont);
    } else {
      round++;
      is_sync = 0;
    }
  }

  if (!is_sync) {
    // size_t f_size = f.getSize();
    uint32_t f_size = MEM_ARR_IN(mem, f, 0, uint32_t);
    // Set **new_sets = new Set *[f.getSize()];
    Addr new_sets = mallocIn.read();

    // spawn_next edgeMap(mem, mallocIn, g_edges, g_size, f, flag_visited,
    // round, d, new_sets, f_size, true);
    edgemap_args edgemap_args0;
    edgemap_args0.counter = f_size;
    edgemap_args0.g_size = g_size;
    edgemap_args0.g_edges = g_edges;
    edgemap_args0.f = f;
    edgemap_args0.flag_visited = flag_visited;
    edgemap_args0.round = round;
    edgemap_args0.d = d;
    edgemap_args0.sets = new_sets;
    edgemap_args0.size = f_size;
    edgemap_args0.is_sync = 1;
    edgeMap_spawn_next sn0;
    sn0.addr = closureIn.read();
    sn0.data = edgemap_args0;
    sn0.allow = f_size;
    sn0.size = 6;

    // uint8_t memoryDependencyVar = 0;

    spawnNext.write(sn0);

    for (uint32_t i = 0; i < f_size; i++) {
      // new_sets[i] = new Set();
      Addr new_sets_i = mallocIn.read();
      MEM_ARR_OUT(mem, new_sets, i, Addr, new_sets_i);
      /*cilk_spawn edgeMapParallel(g, f[i], flag_visited, round, d,
       * new_sets[i]); */
      uint32_t f_i = MEM_ARR_IN(mem, f, i + 1, uint32_t);

      // spawn edgeMapParallel(mem, g_edges, g_size, f_i, flag_visited, round,
      // d, new_sets_i);
      task_args task_args0;
      task_args0.g_edges = g_edges;
      task_args0.vertex = f_i;
      task_args0.flag_visited = flag_visited;
      task_args0.round = round;
      task_args0.d = d;
      task_args0.set = new_sets_i;
      task_args0.cont = sn0.addr;
      taskOutGlobal.write(task_args0);
      // memoryDependencyVar++;
    }
    // sn0.allow = memoryDependencyVar;

    /*cilk_sync;*/
  }
}

/*
void bfs(const Graph &g, Array &flag_visited, Array &d, int r_vertex) {
  Set f;
  f.insert(r_vertex);

  d[r_vertex] = 0;

  int round = 1;
  while (!f.empty()) {
    edgeMap(g, f, flag_visited, round, d, nullptr, 0, false);
    round++;
  }
}
*/
