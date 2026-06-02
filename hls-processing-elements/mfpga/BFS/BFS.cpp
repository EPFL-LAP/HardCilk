#include "util.h"
#include <cstdint>
#include <stdint.h>

static inline addr_t visited_lock_addr(addr_t visited, uint32_t vertex) {
  return visited + ((addr_t)vertex * VISITED_SLOT_BYTES);
}

static bool try_set_and_return_current(hls::stream<lock_req> &toLock,
                                       hls::stream<lock_resp> &fromLock,
                                       addr_t addr, uint32_t &current) {
#pragma HLS INLINE
  lock_req req = make_lock_req(addr, 1, LOCK_OP_SET_AND_RETURN_CURRENT,
                               /*blocking=*/false, ATOMIC_MODE_BYTE);

  bool send_success = false;
  while (!send_success) {
    send_success = toLock.write_nb(req);
  }
  lock_resp resp;
  bool recv_success = false;
  while (!recv_success) {
    recv_success = fromLock.read_nb(resp);
  }
  current = (uint32_t)lock_resp_current_byte(resp, addr);
  return lock_resp_success(resp);
}

static bool testAndSet(void *mem, hls::stream<lock_req> &toLock,
                       hls::stream<lock_resp> &fromLock, addr_t visited,
                       int index) {
#pragma HLS INLINE
  uint32_t current = 1;
  bool lock_accepted = try_set_and_return_current(
      toLock, fromLock, visited_lock_addr(visited, index), current);

  if (!lock_accepted) {
    return false;
  }

  bool first_visitor = current == 0;
  if (first_visitor) {
    MEM_ARR_OUT(mem, visited, index, uint8_t, 1);
  }

  return first_visitor;
}

static bool cond(void *mem, addr_t visited, int v) {
#pragma HLS INLINE
  return MEM_ARR_IN(mem, visited, v, uint8_t) == 0;
}

static bool update(void *mem, hls::stream<lock_req> &toLock,
                   hls::stream<lock_resp> &fromLock, addr_t visited,
                   addr_t distance, addr_t nextFChar, int v,
                   int currentDistance) {
#pragma HLS INLINE
  if (testAndSet(mem, toLock, fromLock, visited, v)) {
    MEM_ARR_OUT(mem, distance, v, int32_t, currentDistance);
    MEM_ARR_OUT(mem, nextFChar, v, uint32_t, 1);
    return true;
  }

  return false;
}

static void init(void *mem, BFS_args &task) {
  for (uint32_t i = 0; i < task.vertex_count; i++) {
#pragma HLS PIPELINE off
    MEM_ARR_OUT(mem, task.distance, i, int32_t, -1);
    MEM_ARR_OUT(mem, task.visited, i, uint8_t, 0);
    MEM_ARR_OUT(mem, task.nextFChar, i, uint32_t, 0);
  }

  // The root PE is the only one running during init, so the source vertex can
  // be marked visited with a plain write -- no lock arbitration is needed yet.
  MEM_ARR_OUT(mem, task.distance, task.source, int32_t, 0);
  MEM_ARR_OUT(mem, task.visited, task.source, uint8_t, 1);
  MEM_ARR_OUT(mem, task.frontier0, 0, uint32_t, task.source);

  task.currentDistance = 1;
  task.frontier_length = 1;
  task.active = 0;
  task.done = 0;
}

static uint32_t pack_next_frontier(void *mem, BFS_args &task) {
  addr_t next_frontier = task.active == 0 ? task.frontier1 : task.frontier0;
  uint32_t next_length = 0;

  for (uint32_t i = 0; i < task.vertex_count; i++) {
#pragma HLS PIPELINE off
    uint32_t is_next = MEM_ARR_IN(mem, task.nextFChar, i, uint32_t);
    if (is_next != 0) {
      MEM_ARR_OUT(mem, next_frontier, next_length, uint32_t, i);
      next_length++;
      MEM_ARR_OUT(mem, task.nextFChar, i, uint32_t, 0);
    }
  }

  task.active = 1 - task.active;
  task.frontier_length = next_length;
  task.currentDistance++;
  return next_length;
}

static void store_continuation(void *mem, BFS_args &task) {
#pragma HLS INLINE
  MEM_OUT(mem, task.cont + 4, uint32_t, task.source);
  MEM_OUT(mem, task.cont + 8, uint32_t, task.vertex_count);
  MEM_OUT(mem, task.cont + 12, uint32_t, task.currentDistance);
  MEM_OUT(mem, task.cont + 16, uint32_t, task.max_depth);
  MEM_OUT(mem, task.cont + 20, uint32_t, task.frontier_length);
  MEM_OUT(mem, task.cont + 24, uint32_t, task.active);
  MEM_OUT(mem, task.cont + 28, uint32_t, task.done);
  MEM_OUT(mem, task.cont + 32, addr_t, task.graph);
  MEM_OUT(mem, task.cont + 40, addr_t, task.distance);
  MEM_OUT(mem, task.cont + 48, addr_t, task.visited);
  MEM_OUT(mem, task.cont + 56, addr_t, task.frontier0);
  MEM_OUT(mem, task.cont + 64, addr_t, task.frontier1);
  MEM_OUT(mem, task.cont + 72, addr_t, task.nextFChar);
  MEM_OUT(mem, task.cont + 80, addr_t, task.cont);
  MEM_OUT(mem, task.cont, uint32_t, task.counter);
}

void BFS(void *mem_0, hls::stream<sparse_edgemap_helper_args> &taskOutGlobal,
         hls::stream<BFS_args> &taskIn) {
#pragma HLS INTERFACE ap_ctrl_none port = return

#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = taskOutGlobal

#pragma HLS INTERFACE mode = m_axi port = mem_0 bundle = gmem channel =        \
    0 latency = 32 num_write_outstanding = 16 num_read_outstanding =           \
        16 max_write_burst_length = 16 max_read_burst_length =                 \
            16 max_widen_bitwidth = 256

  auto task = taskIn.read();

  bool first_iteration = task.currentDistance == 0 && task.frontier_length == 0;
  if (first_iteration) {
    init(mem_0, task);
  } else {
    uint32_t next_length = pack_next_frontier(mem_0, task);
    if (next_length == 0 || task.currentDistance > task.max_depth) {
      task.done = 1;
      task.counter = 1;
      store_continuation(mem_0, task);
      return;
    }
  }

  task.counter = task.frontier_length;
  store_continuation(mem_0, task);

  addr_t frontier = task.active == 0 ? task.frontier0 : task.frontier1;
  for (uint32_t i = 0; i < task.frontier_length; i++) {
#pragma HLS PIPELINE II = 1
    sparse_edgemap_helper_args helper_task;
    helper_task.graph = task.graph;
    helper_task.distance = task.distance;
    helper_task.visited = task.visited;
    helper_task.frontier = frontier;
    helper_task.nextFChar = task.nextFChar;
    helper_task.cont = task.cont;
    helper_task.index = i;
    helper_task.currentDistance = task.currentDistance;
    helper_task.vertex_count = task.vertex_count;
    helper_task.max_depth = task.max_depth;
    taskOutGlobal.write(helper_task);
  }
}

void sparse_edgemap_helper(void *mem_0, void *mem_1, void *mem_2,
                           hls::stream<sparse_edgemap_helper_args> &taskIn,
                           hls::stream<uint64_t> &argOut,
                           hls::stream<lock_req> &toLock,
                           hls::stream<lock_resp> &fromLock) {
#pragma HLS INTERFACE ap_ctrl_none port = return

#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = argOut
#pragma HLS INTERFACE mode = axis port = toLock
#pragma HLS INTERFACE mode = axis port = fromLock

#pragma HLS INTERFACE mode = m_axi port = mem_0 bundle = gmem channel =        \
    0 latency = 32 num_write_outstanding = 1 num_read_outstanding =            \
        64 max_read_burst_length = 16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_1 bundle = gmem channel =        \
    1 latency = 32 num_write_outstanding = 1 num_read_outstanding =            \
        64 max_read_burst_length = 16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_2 bundle = gmem channel =        \
    2 latency = 32 num_write_outstanding = 16 num_read_outstanding =           \
        16 max_write_burst_length = 16 max_read_burst_length =                 \
            16 max_widen_bitwidth = 256

  auto task = taskIn.read();

  if (task.currentDistance > task.max_depth) {
    argOut.write(task.cont);
    return;
  }

  uint32_t u = MEM_ARR_IN(mem_0, task.frontier, task.index, uint32_t);
  if (u >= task.vertex_count) {
    argOut.write(task.cont);
    return;
  }

  addr_t neighbors = MEM_IN(mem_0, task.graph + ((addr_t)u << 4), addr_t);
  uint32_t degree = MEM_IN(mem_0, task.graph + ((addr_t)u << 4) + 8, uint64_t);

  for (int j = 0; j < degree; j++) {
#pragma HLS PIPELINE off
    int neighbor = MEM_ARR_IN(mem_1, neighbors, j, uint32_t);
    if (neighbor < task.vertex_count && cond(mem_2, task.visited, neighbor)) {
      update(mem_2, toLock, fromLock, task.visited, task.distance,
             task.nextFChar, neighbor, task.currentDistance);
    }
  }

  argOut.write(task.cont);
}
