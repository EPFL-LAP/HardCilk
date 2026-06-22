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

// 144-bit AXI-Stream lock request/response payloads, matching
// LockServer.{Req,Resp}Width. The generated lock server wrapper uses
// ready/valid/data only, so the packet type is the raw tdata payload.
using lock_req = ap_uint<144>;
using lock_resp = ap_uint<144>;

// Opcodes accepted by the LockServer (lockchisel.Operation). The operation
// field of a request lives at tdata[131:128].
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

// Atomic granularity selector (lockchisel.AtomicMode), carried in
// tdata[134:133] of a lock request. The AMU read-modify-writes only the
// selected sub-field of the 64-bit beat (byte-strobed), so Visited can be a
// plain byte array.
enum AtomicMode : uint8_t
{
  ATOMIC_MODE_DOUBLEWORD = 0b00, // 8 bytes (legacy 64-bit behaviour)
  ATOMIC_MODE_BYTE = 0b01,       // 1 byte
  ATOMIC_MODE_WORD = 0b10,       // 4 bytes
};

// Byte-addressed atomic slots are one byte wide. Addresses stay 64-bit on the
// wire (truncated to the HBM address width).
static const addr_t VISITED_SLOT_BYTES = 1;

// Number of vertices one chunked vertex-map helper handles.
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

// Build a lock request beat:
//   tdata[63:0]    = byte address of the slot (tag)
//   tdata[127:64]  = operand / store value (data)
//   tdata[131:128] = opcode
//   tdata[132]     = blocking
//   tdata[134:133] = atomic mode (00 = double-word, 01 = byte, 10 = word)
//   tdata[135]     = float-compare flag: when set, the conditional SET_IF_* ops
//                    order operand vs memory as IEEE-754 floats instead of ints
//   tdata[143:136] = sender metadata, echoed back in the response so a PE with
//                    several requests in flight can correlate completions
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

// Decode a lock response beat. The LockServer packs
// Cat(meta, prevValue, tag, writeOccurred, success), so:
//   tdata[0]       = success (1 == request completed)
//   tdata[1]       = for conditional AMU ops (SET_IF_GREATER / SET_IF_LESS),
//                    whether the store actually happened; for every other op it
//                    just mirrors the success bit
//   tdata[7:2]     = reserved (0)
//   tdata[71:8]    = echoed request tag (the lock address)
//   tdata[135:72]  = previous memory contents, with the addressed sub-word
//                    right-justified into the low bits (byte/word atomics return
//                    just their lane in [7:0]/[31:0]; doubleword is the full beat)
//   tdata[143:136] = sender metadata echoed from the request
static inline bool lock_resp_success(const lock_resp &resp)
{
#pragma HLS INLINE
  return resp(0, 0) != 0;
}

// For a conditional AMU op (SET_IF_GREATER / SET_IF_LESS), true iff the store
// actually happened (the predicate held). For all other ops this mirrors
// lock_resp_success.
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

// The AMU right-justifies the addressed byte into the low bits of the returned
// value, so the previous Visited byte is the low byte of lock_resp_current
// (tdata[79:72]). No shift needed.
static inline uint8_t lock_resp_current_byte(const lock_resp &resp)
{
#pragma HLS INLINE
  return (uint8_t)(lock_resp_current(resp) & 0xFF);
}
