#pragma once

#include "ap_axi_sdata.h"
#include "ap_int.h"
#include "hls_stream.h"
#include <cstdint>
#include <stddef.h>
#include <stdint.h>

#ifndef BFS_LEGACY_ARGUMENT_NOTIFIER
#define BFS_LEGACY_ARGUMENT_NOTIFIER 0
#endif
#if BFS_LEGACY_ARGUMENT_NOTIFIER != 0 && BFS_LEGACY_ARGUMENT_NOTIFIER != 1
#error "BFS_LEGACY_ARGUMENT_NOTIFIER must be 0 or 1"
#endif

#define MEM_OUT_VOLATILE(mem_port, addr, type, value) \
  *((volatile type *)((uint8_t *)(mem_port) + (addr))) = (value)

#define MEM_IN_VOLATILE(mem_port, addr, type) \
  *((volatile type *)((uint8_t *)(mem_port) + (addr)))

#define MEM_ARR_OUT_VOLATILE(mem_port, addr, idx, type, value) \
  *((volatile type *)((uint8_t *)(mem_port) + (addr) + (idx) * sizeof(type))) = (value)

#define MEM_ARR_IN_VOLATILE(mem_port, addr, idx, type) \
  *((volatile type *)((uint8_t *)(mem_port) + (addr) + (idx) * sizeof(type)))

#define MEM_OUT(mem_port, addr, type, value) \
  *((type(*))((uint8_t *)(mem_port) + (addr))) = (value)
#define MEM_IN(mem_port, addr, type) \
  *((type(*))((uint8_t *)(mem_port) + (addr)))

#define MEM_ARR_OUT(mem_port, addr, idx, type, value) \
  *((type(*))((uint8_t *)(mem_port) + (addr) + (idx) * sizeof(type))) = (value)
#define MEM_ARR_IN(mem_port, addr, idx, type) \
  *((type(*))((uint8_t *)(mem_port) + (addr) + (idx) * sizeof(type)))

#define TASK_FIELD_ADDR(task, field) (uint64_t)&(((BFS_args *)task.cont)->field)

using addr_t = uint64_t;
using Addr = uint64_t;

using lock_req = ap_axiu<144, 0, 0, 0>;
using lock_resp = ap_axiu<136, 0, 0, 0>;

enum LockOperation : uint8_t
{
  LOCK_OP_UNLOCK = 0b0000,
  LOCK_OP_LOCK = 0b0001,
  LOCK_OP_SET_AND_RETURN_CURRENT = 0b0010,
  LOCK_OP_SET_IF_GREATER_AND_RETURN_CURRENT = 0b0011,
  LOCK_OP_SET_IF_LESS_AND_RETURN_CURRENT = 0b0100,
  LOCK_OP_ADD_N_RETURN_CURRENT = 0b0101,
  LOCK_OP_UNLOCK_NO_RESPONSE = 0b0111,
};

enum BfsVisitFlags : uint32_t
{
  BFS_VISIT_VERTEX_ALREADY_MARKED = 1
};

enum AtomicMode : uint8_t
{
  ATOMIC_MODE_DOUBLEWORD = 0b00, // 8 bytes
  ATOMIC_MODE_BYTE = 0b01,       // 1 byte
  ATOMIC_MODE_WORD = 0b10,       // 4 bytes
};


static const addr_t VISITED_SLOT_BYTES = 1;


#ifndef VERTICES_PER_TASK
#define VERTICES_PER_TASK 64
#endif

struct BFS_args
{
  uint32_t counter;
  uint32_t source;
  uint32_t vertex_count;
  uint32_t currentDistance;
  uint32_t max_depth;
  uint32_t frontier_length;
  uint32_t active;
  uint32_t done;
  addr_t graph;
  addr_t distance;
  addr_t visited;
  addr_t frontier0;
  addr_t frontier1;
  addr_t nextFChar;
  addr_t cont;
  addr_t status;
  uint8_t _padding[32];
};

struct __attribute__((packed)) sparse_edgemap_helper_args
{
  addr_t cont;                 // 0
  uint32_t continuation_meta;  // 8, stamped by the spawnNext write buffer
  addr_t graph;                // 12
  addr_t distance;             // 20
  addr_t visited;              // 28
  addr_t frontier;             // 36
  addr_t next_frontier;        // 44
  addr_t nextFChar;            // 52
  uint32_t index;              // 60
  uint32_t currentDistance;    // 64
  uint32_t vertex_count;       // 68  total vertices in the graph (bounds checks)
  uint32_t max_depth;          // 72
  uint32_t task_vertex_count;  // 76  frontier vertices in THIS chunk
  uint8_t _pad[48]; // 80..127
};
static_assert(sizeof(sparse_edgemap_helper_args) == 128,
              "sparse_edgemap_helper_args must be 1024 bits (widthTask=1024)");
static_assert(offsetof(sparse_edgemap_helper_args, continuation_meta) == 8,
              "metadata must land where the write buffer stamps it");

// Host-visible progress is separate from the cached continuation address,
// which changes on every BFS level.
struct __attribute__((packed)) bfs_status {
  uint32_t done;
  uint32_t currentDistance;
  uint32_t frontier_length;
  uint32_t reserved;
};

// One continuation-line write followed by release of `allow` helper tasks.
// The padding makes the Vitis AXIS packet the next power-of-two width.
struct bfs_spawn_next {
  addr_t addr;
  BFS_args data;
  uint32_t size;
  uint32_t allow;
  uint8_t _padding[112];
};

#if !BFS_LEGACY_ARGUMENT_NOTIFIER
struct __attribute__((packed)) bfs_counter_update {
  addr_t address;
  uint32_t continuation_meta;
  ap_uint<8> payload;
  ap_uint<7> offset;
};
#endif

static_assert(sizeof(BFS_args) == 128, "BFS continuation ABI");
static_assert(sizeof(bfs_status) == 16, "BFS host status ABI");
static_assert(sizeof(bfs_spawn_next) == 256, "BFS spawnNext packet ABI");

static inline lock_req make_lock_req(addr_t address, ap_uint<64> value,
                                     LockOperation op, bool blocking,
                                     AtomicMode atomic_mode, uint8_t metadata = 0,
                                     bool float_compare = false)
{
#pragma HLS INLINE
  lock_req req;
  req.data = 0;
  req.data(63, 0) = (ap_uint<64>)address;
  req.data(127, 64) = value;
  req.data(131, 128) = (uint8_t)op;
  req.data(132, 132) = blocking ? 1 : 0;
  req.data(134, 133) = (uint8_t)atomic_mode;
  req.data(135, 135) = float_compare ? 1 : 0;
  req.data(143, 136) = metadata;
  req.keep = -1;
  req.strb = -1;
  req.last = 1;
  return req;
}

static inline bool lock_resp_success(const lock_resp &resp)
{
#pragma HLS INLINE
  return resp.data(0, 0) != 0;
}


static inline bool lock_resp_write_occurred(const lock_resp &resp)
{
#pragma HLS INLINE
  return resp.data(1, 1) != 0;
}

static inline ap_uint<64> lock_resp_current(const lock_resp &resp)
{
#pragma HLS INLINE
  return resp.data(135, 72);
}

static inline ap_uint<64> lock_resp_tag(const lock_resp &resp)
{
#pragma HLS INLINE
  return resp.data(71, 8);
}

static inline uint8_t lock_resp_current_byte(const lock_resp &resp)
{
#pragma HLS INLINE
  return (uint8_t)(lock_resp_current(resp) & 0xFF);
}
