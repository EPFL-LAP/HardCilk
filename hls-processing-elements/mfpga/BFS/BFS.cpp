#include "ap_int.h"
#include "util.h"
#include <cstdint>
#include <stdint.h>

// ---------------------------------------------------------
// Helper Functions
// ---------------------------------------------------------

static inline addr_t visited_lock_addr(addr_t visited, uint32_t vertex) {
  return visited + ((addr_t)vertex * VISITED_SLOT_BYTES);
}

static bool cond(void *mem, addr_t visited, int v) {
#pragma HLS INLINE
  return MEM_ARR_IN(mem, visited, v, uint8_t) == 0;
}

// nextFChar is a single fixed-address counter that the LockServer's AMU (a
// separate AXI master HLS cannot see) read-modify-writes. Without volatile, HLS
// treats mem_0 as exclusively owned by this PE and can forward stale local
// values to later reads. volatile forces actual AXI transactions.
static inline uint64_t read_counter(void *mem, addr_t addr) {
#pragma HLS INLINE
  return *((volatile uint64_t *)((uint8_t *)mem + addr));
}
static inline void write_counter(void *mem, addr_t addr, uint64_t value) {
#pragma HLS INLINE
  *((volatile uint64_t *)((uint8_t *)mem + addr)) = value;
}

static void init(void *mem, BFS_args &task) {
  // Pipelined Initialization
  for (uint32_t i = 0; i < task.vertex_count; i++) {
#pragma HLS PIPELINE II = 1
    MEM_ARR_OUT(mem, task.distance, i, int32_t, -1);
    MEM_ARR_OUT(mem, task.visited, i, uint8_t, 0);
  }

  MEM_ARR_OUT(mem, task.distance, task.source, int32_t, 0);
  MEM_ARR_OUT(mem, task.visited, task.source, uint8_t, 1);
  MEM_ARR_OUT(mem, task.frontier0, 0, uint32_t, task.source);
  write_counter(mem, task.nextFChar, 0);

  task.currentDistance = 1;
  task.frontier_length = 1;
  task.active = 0;
  task.done = 0;
}

static void store_continuation(void *mem, BFS_args &task) {
#pragma HLS INLINE
  // Write every field of the continuation closure *except* counter first, then
  // publish counter last. The framework re-injects the continuation the moment
  // counter is decremented to 0, so counter must become live only after the
  // rest of the closure (frontier ptrs, currentDistance, active, done, ...) is
  // in HBM -- otherwise a helper could fire the continuation over a
  // half-written struct.
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

// ---------------------------------------------------------
// Main BFS Kernel
// ---------------------------------------------------------

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
    uint64_t next_length = read_counter(mem_0, task.nextFChar);
    if (next_length == 0 || task.currentDistance > task.max_depth) {
      task.done = 1;
      task.counter = 1;
      store_continuation(mem_0, task);
      return;
    }
    write_counter(mem_0, task.nextFChar, 0);
    task.frontier_length = (uint32_t)next_length;
    task.active = 1 - task.active;
    task.currentDistance++;
  }

  task.counter = task.frontier_length;
  store_continuation(mem_0, task);

  addr_t current_frontier = task.active == 0 ? task.frontier0 : task.frontier1;
  addr_t next_frontier = task.active == 0 ? task.frontier1 : task.frontier0;

  for (uint32_t i = 0; i < task.frontier_length; i++) {
#pragma HLS PIPELINE II = 1
    sparse_edgemap_helper_args helper_task;
    helper_task.graph = task.graph;
    helper_task.distance = task.distance;
    helper_task.visited = task.visited;
    helper_task.frontier = current_frontier;
    helper_task.next_frontier = next_frontier;
    helper_task.nextFChar = task.nextFChar;
    helper_task.cont = task.cont;
    helper_task.index = i;
    helper_task.currentDistance = task.currentDistance;
    helper_task.vertex_count = task.vertex_count;
    helper_task.max_depth = task.max_depth;
    taskOutGlobal.write(helper_task);
  }
}

// ---------------------------------------------------------
// Edge-map helper internals
// ---------------------------------------------------------

// One outstanding LockServer request awaiting its response. Because the
// LockServer ALWAYS returns responses in request order, a single in-order FIFO
// of these contexts is enough to interpret each response beat as it arrives --
// no per-request routing/tagging is needed.
enum inflight_kind : uint8_t {
  INFLIGHT_VISIT = 0,
  INFLIGHT_FRONTIER = 1,
};

struct inflight_ctx {
  uint8_t kind;
  uint32_t neighbor;
};

// Bound on simultaneously in-flight lock requests. Keeps the internal context
// FIFOs (and the LockServer's buffering) from overflowing.
static const uint32_t LOCK_WINDOW = 32;

// Drive one vertex's neighbour scan, the Visited test-and-set, and the
// next-frontier append from a SINGLE loop. This deliberately replaces the old
// generator/arbiter/router/consumer dataflow: that design fed the consumer's
// frontier requests back into the arbiter (consumer -> cons_reqs -> arbiter ->
// router_tags -> router -> resps -> consumer), an internal cycle that
// #pragma HLS DATAFLOW does not support. Here everything is one loop, so the
// feedback is just a legal loop-carried dependency.
//
// Deadlock-freedom: draining fromLock is always the top priority, and new
// requests are only issued while fewer than LOCK_WINDOW are outstanding -- and
// only when fromLock is empty, i.e. the LockServer's response side has room to
// make forward progress, so toLock.write() can never wedge.
static uint32_t edgemap_process(void *mem_0, void *mem_1, void *mem_2,
                                sparse_edgemap_helper_args task,
                                addr_t neighbors, uint32_t degree,
                                hls::stream<lock_req> &toLock,
                                hls::stream<lock_resp> &fromLock) {
  hls::stream<inflight_ctx> inflight("inflight");
  hls::stream<uint32_t> pending_frontier("pending_frontier");
#pragma HLS STREAM variable = inflight depth = 64
#pragma HLS STREAM variable = pending_frontier depth = 64

  uint32_t j = 0;           // next neighbour to scan
  uint32_t outstanding = 0; // lock requests sent but not yet answered
  bool done = false;

  // A single request that has been DECIDED but not yet handed to the LockServer.
  // toLock is wired straight into the server's depth-2 per-PE input queue (no
  // intervening FIFO), which is far shallower than LOCK_WINDOW. A *blocking*
  // toLock.write() would therefore stall this II=1 pipeline whenever the server
  // is momentarily busy with the other PEs -- and a stalled pipeline stops
  // draining fromLock, which is the actual deadlock (the helper can no longer
  // answer the responses the server is waiting to deliver). Instead we stage one
  // request and offer it with a NON-BLOCKING write_nb: if toLock is full we just
  // retry on a later iteration, so draining fromLock is *always* possible. The
  // ADD_ONE keeps its server-side blocking bit (retry-until-win for a unique
  // slot); only the stream hand-off is made non-blocking.
  bool have_staged = false;
  lock_req staged_req;
  inflight_ctx staged_ctx;

  // ---- DEBUG counters (stashed into cont padding at the end) ----
  uint32_t dbg_locks_sent = 0;
  uint32_t dbg_winners = 0;
  uint32_t dbg_appends = 0;
  uint32_t dbg_first_success = 2; // 2 == no visited response observed
  uint32_t dbg_first_current = 0xFF;
  uint32_t dbg_first_neighbor = 0xFFFFFFFF;
  bool dbg_first_seen = false;

  while (!done) {
#pragma HLS PIPELINE II = 1
    // PRIORITY 1: drain a response (frees the LockServer's response side). Only
    // ever reads when non-empty, so this can never stall the pipeline.
    if (!fromLock.empty()) {
      lock_resp resp = fromLock.read();
      inflight_ctx ctx = inflight.read();
      outstanding--;

      if (ctx.kind == INFLIGHT_VISIT) {
        uint8_t current = lock_resp_current_byte(
            resp, visited_lock_addr(task.visited, ctx.neighbor));
        if (!dbg_first_seen) {
          dbg_first_seen = true;
          dbg_first_success = lock_resp_success(resp) ? 1 : 0;
          dbg_first_current = current;
          dbg_first_neighbor = ctx.neighbor;
        }
        if (lock_resp_success(resp) && current == 0) {
          // First visitor: record distance and enqueue an append request for
          // this same in-order loop.
          MEM_ARR_OUT(mem_2, task.visited, ctx.neighbor, uint8_t, 1);
          MEM_ARR_OUT(mem_2, task.distance, ctx.neighbor, int32_t,
                      task.currentDistance);
          pending_frontier.write(ctx.neighbor);
          dbg_winners++;
        }
      } else {
        uint64_t slot = lock_resp_current(resp);
        MEM_ARR_OUT(mem_2, task.next_frontier, slot, uint32_t, ctx.neighbor);
        dbg_appends++;
      }
    }
    // PRIORITY 2: hand the staged request to the LockServer, non-blocking. If
    // toLock is full this fails silently and we retry next iteration (after
    // draining more responses, which frees the server to accept).
    else if (have_staged) {
      if (toLock.write_nb(staged_req)) {
        inflight.write(staged_ctx);
        outstanding++;
        have_staged = false;
      }
    }
    // PRIORITY 3: stage an append of a newly discovered vertex (server-side
    // blocking ADD_ONE so every append wins a unique next-frontier slot).
    else if (!pending_frontier.empty() && outstanding < LOCK_WINDOW) {
      uint32_t neighbor = pending_frontier.read();
      staged_req = make_lock_req(task.nextFChar, 1, LOCK_OP_ADD_ONE_RETURN_CURRENT,
                                 true, ATOMIC_MODE_DOUBLEWORD);
      staged_ctx = {INFLIGHT_FRONTIER, neighbor};
      have_staged = true;
    }
    // PRIORITY 4: stage the Visited test-and-set for the next neighbour.
    else if (j < degree && outstanding < LOCK_WINDOW) {
      int neighbor = MEM_ARR_IN(mem_1, neighbors, j, uint32_t);
      j++;
      if (neighbor < task.vertex_count && cond(mem_2, task.visited, neighbor)) {
        staged_req = make_lock_req(visited_lock_addr(task.visited, neighbor), 1,
                                   LOCK_OP_SET_AND_RETURN_CURRENT, false,
                                   ATOMIC_MODE_BYTE);
        staged_ctx = {INFLIGHT_VISIT, (uint32_t)neighbor};
        have_staged = true;
        dbg_locks_sent++;
      }
    }

    if (j == degree && outstanding == 0 && pending_frontier.empty() &&
        !have_staged)
      done = true;
  }

  // ---- DEBUG: stash results into cont padding (bytes 100..123).
  MEM_OUT(mem_0, task.cont + 100, uint32_t, dbg_locks_sent);
  MEM_OUT(mem_0, task.cont + 104, uint32_t, dbg_winners);
  MEM_OUT(mem_0, task.cont + 108, uint32_t, dbg_appends);
  MEM_OUT(mem_0, task.cont + 112, uint32_t, dbg_first_success);
  MEM_OUT(mem_0, task.cont + 116, uint32_t, dbg_first_current);
  MEM_OUT(mem_0, task.cont + 120, uint32_t, dbg_first_neighbor);
  return dbg_winners;
}

// ---------------------------------------------------------
// Top Level Edge Map Helper
// ---------------------------------------------------------

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

  // DEBUG marker (cont+96): which path the helper took. Only meaningful for the
  // single source helper at level 1; later helpers overwrite it.
  if (task.currentDistance > task.max_depth) {
    MEM_OUT(mem_0, task.cont + 96, uint32_t, 0xE1); // over max_depth
    argOut.write(task.cont);
    return;
  }

  uint32_t u = MEM_ARR_IN(mem_0, task.frontier, task.index, uint32_t);
  MEM_OUT(mem_0, task.cont + 88, uint32_t,
          u); // DEBUG: vertex read from frontier
  if (u >= task.vertex_count) {
    MEM_OUT(mem_0, task.cont + 96, uint32_t, 0xE2); // u out of range
    argOut.write(task.cont);
    return;
  }

  // graph[u] = { neighbors_ptr (8B), degree (8B) }. Read the two 64-bit fields
  // separately -- a single ap_uint<128> load over the widened m_axi was
  // returning degree==0, which made every helper early-return.
  addr_t neighbors = MEM_IN(mem_0, task.graph + ((addr_t)u << 4), addr_t);
  uint32_t degree = MEM_IN(mem_0, task.graph + ((addr_t)u << 4) + 8, uint64_t);
  MEM_OUT(mem_0, task.cont + 92, uint32_t, degree); // DEBUG: degree read

  if (degree == 0) {
    MEM_OUT(mem_0, task.cont + 96, uint32_t, 0xE3); // degree == 0
    argOut.write(task.cont);
    return;
  }

  MEM_OUT(mem_0, task.cont + 96, uint32_t, 0xA0); // reached lock loop
  uint32_t winners = edgemap_process(mem_0, mem_1, mem_2, task, neighbors,
                                     degree, toLock, fromLock);
  // argOut is the continuation trigger: the framework re-injects BFS once every
  // helper's argOut has decremented the join counter. A bare argOut.write() has
  // no dependency on edgemap_process's lock-stream work, so HLS would schedule it
  // early -- firing the continuation before this helper's appends are committed,
  // and the parent then reads a half-built next frontier. Gate the send on the
  // return value (a token only available after the loop drains) so HLS keeps it
  // ordered after edgemap_process. The sentinel compare is never true at runtime,
  // so the send always happens; write_nb in a spin loop keeps the send itself
  // from being optimized away.
  bool sent = (winners == 0xDEADBEEFu);
  while (!sent) {
#pragma HLS PIPELINE off
    sent = argOut.write_nb(task.cont);
  }
}
