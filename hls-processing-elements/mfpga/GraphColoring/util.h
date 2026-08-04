#pragma once

#include "ap_axi_sdata.h"
#include "ap_int.h"
#include "hls_stream.h"
#include <cstdint>
#include <stddef.h>
#include <stdint.h>

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

#define TASK_FIELD_ADDR(task, field) (uint64_t)&(((GraphColoring_args *)task.cont)->field)

using addr_t = uint64_t;
using Addr = uint64_t;

using lock_req = ap_uint<144>;
using lock_resp = ap_uint<144>;


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

struct GraphColoring_args
{
  uint32_t counter;          // 0
  uint32_t vertex_count;     // 4
  uint32_t init_done;        // 8
  uint32_t active;           // 12  roots buffer to process this pass
  uint32_t done;             // 16
  uint32_t finished;         // 20  colored vertices
  uint32_t frontier_length;  // 24  current roots length
  uint32_t max_colors;       // 28
  addr_t graph;              // 32
  addr_t rank;               // 40  permutation/rank; lower rank wins ties
  addr_t priority;           // 48  mutable predecessor counts
  addr_t color;              // 56  output color per vertex, UINT32_MAX while uncolored
  addr_t roots0;             // 64
  addr_t roots1;             // 72
  addr_t nextFChar;          // 80  atomic counter for next roots
  addr_t colorsUsed;         // 88  max color index used plus one
  addr_t cont;               // 96
  uint8_t _padding[24];
};

static_assert(sizeof(GraphColoring_args) == 128,
              "GraphColoring_args must be 1024 bits (widthTask=1024)");

struct color_init_helper_args
{
  addr_t graph;              // 0
  addr_t rank;               // 8
  addr_t priority;           // 16
  addr_t color;              // 24
  addr_t roots;              // 32
  addr_t nextFChar;          // 40
  addr_t cont;               // 48
  uint32_t index;            // 56
  uint32_t vertex_count;     // 60
  uint32_t task_vertex_count;// 64
  uint32_t max_colors;       // 68
  uint8_t _padding[56];
};

static_assert(sizeof(color_init_helper_args) == 128,
              "color_init_helper_args must be 1024 bits (widthTask=1024)");

struct color_loop_helper_args
{
  addr_t graph;              // 0
  addr_t priority;           // 8
  addr_t color;              // 16
  addr_t current_roots;      // 24
  addr_t next_roots;         // 32
  addr_t nextFChar;          // 40
  addr_t colorsUsed;         // 48
  addr_t cont;               // 56
  uint32_t index;            // 64
  uint32_t frontier_length;  // 68
  uint32_t task_vertex_count;// 72
  uint32_t max_colors;       // 76
  uint32_t vertex_count;     // 80
  uint8_t _padding[44];
};

static_assert(sizeof(color_loop_helper_args) == 128,
              "color_loop_helper_args must be 1024 bits (widthTask=1024)");

static inline lock_req make_lock_req(addr_t address, ap_uint<64> value,
                                     LockOperation op, bool blocking,
                                     AtomicMode atomic_mode, uint8_t metadata = 0,
                                     bool float_compare = false)
{
#pragma HLS INLINE
  lock_req req;
  req = 0;
  req(63, 0) = (ap_uint<64>)address;
  req(127, 64) = value;
  req(131, 128) = (uint8_t)op;
  req(132, 132) = blocking ? 1 : 0;
  req(134, 133) = (uint8_t)atomic_mode;
  req(135, 135) = float_compare ? 1 : 0;
  req(143, 136) = metadata;
  return req;
}


static inline bool lock_resp_success(const lock_resp &resp)
{
#pragma HLS INLINE
  return resp(0, 0) != 0;
}


static inline bool lock_resp_write_occurred(const lock_resp &resp)
{
#pragma HLS INLINE
  return resp(1, 1) != 0;
}

static inline ap_uint<64> lock_resp_current(const lock_resp &resp)
{
#pragma HLS INLINE
  return resp(135, 72);
}

static inline ap_uint<64> lock_resp_tag(const lock_resp &resp)
{
#pragma HLS INLINE
  return resp(71, 8);
}

static inline uint8_t lock_resp_metadata(const lock_resp &resp)
{
#pragma HLS INLINE
  return (uint8_t)resp(143, 136);
}


static inline uint8_t lock_resp_current_byte(const lock_resp &resp)
{
#pragma HLS INLINE
  return (uint8_t)(lock_resp_current(resp) & 0xFF);
}
