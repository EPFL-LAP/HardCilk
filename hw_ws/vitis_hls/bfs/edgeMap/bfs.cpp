#include "../descriptors.h"
#include <cstddef>
#include <cstdint>

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

void edgeMapParallel(void *mem, hls::stream<task_args> &taskIn,
                     hls::stream<uint64_t> &argOut) {
#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = argOut
#pragma HLS INTERFACE mode = m_axi port = mem
  // The arguments
  auto task = taskIn.read();
  uint32_t g_size = task.g_size;
  uint32_t vertex = task.vertex;
  uint16_t round = task.round;
  Addr g_edges = task.g_edges;
  Addr flag_visited = task.flag_visited;
  Addr d = task.d;
  Addr set = task.set;
  uint64_t cont = task.cont;

  // TODO: THIS IS A DAE, HOW TO DO IT IN HLS?
  for (uint32_t i = 0; i < g_size; i++) {
    // auto edge = g.edges[i];
    auto edge = MEM_ARR_IN(mem, g_edges, i, Pair32);
    uint32_t neighbor;
    if (edge.first == vertex) {
      neighbor = edge.second;
    } else if (edge.second == vertex) {
      neighbor = edge.first;
    }

    if (edge.first == vertex || edge.second == vertex) {
      if (cond(mem, flag_visited, neighbor) &&
          update(mem, flag_visited, neighbor, d, round))
        set_insert(mem, set, neighbor);
    }
  }

  argOut.write(cont);
}

void edgeMap(void *mem, hls::stream<uint64_t> &mallocIn,
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
#pragma HLS INTERFACE mode = m_axi port = mem
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

  if (is_sync) {
    // f.clear();
    MEM_ARR_OUT(mem, f, 0, uint32_t, 0);
    uint32_t f_count = 0;

    for (int i = 0; i < size; i++) {
      Addr set_i = MEM_ARR_IN(mem, sets, i, Addr);
      uint32_t set_i_size = MEM_ARR_IN(mem, set_i, 0, Addr);
      for (int j = 0; j < set_i_size; j++) {
        // f.insert((*sets[i])[j]);
        uint32_t set_i_j = MEM_ARR_IN(mem, set_i, j + 1, uint32_t);
        set_insert(mem, f, set_i_j);
        f_count++;
      }
    }

    if (f_count == 0) {
      argOut.write(task.cont);
      return;
    } else {
      round++;
      is_sync = 0;
    }
  }

  if (!is_sync) {
    // size_t f_size = f.getSize();
    size_t f_size = MEM_ARR_IN(mem, f, 0, uint32_t);
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
      task_args0.g_size = g_size;
      task_args0.vertex = f_i;
      task_args0.flag_visited = flag_visited;
      task_args0.round = round;
      task_args0.d = d;
      task_args0.set = new_sets_i;
      task_args0.cont = sn0.addr;
      taskOutGlobal.write(task_args0);
    }
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
