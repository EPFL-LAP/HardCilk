#pragma once

#include "ap_axi_sdata.h"
#include "ap_fixed.h"
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

#define TASK_FIELD_ADDR(task, field) (uint64_t)&(((ApproxDenseSub_args *)task.cont)->field)

using addr_t = uint64_t;
using Addr = uint64_t;
using epsilon_t = ap_ufixed<32, 16>;
using density_t = ap_ufixed<64, 32>;

// 136-bit AXI-Stream lock request/response, matching the lockchisel.LockServer
// wire format (LockServer.ReqWidth == LockServer.RespWidth == 136).
using lock_req = ap_axiu<144, 0, 0, 0>;
using lock_resp = ap_axiu<136, 0, 0, 0>;

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

// Number of active-set vertices the orchestration kernel packs into one helper
// task (one chunk).
#ifndef VERTICES_PER_TASK
#define VERTICES_PER_TASK 64
#endif

struct ApproxDenseSub_args
{
  uint32_t counter;
  uint32_t source;
  uint32_t vertex_count;
  uint32_t round;
  epsilon_t epsilon;
  uint32_t frontier_length;
  uint32_t active;
  uint32_t done;
  addr_t graph;
  addr_t degree;
  addr_t frontier0;
  addr_t frontier1;
  addr_t frontier2;
  addr_t nextFChar;
  addr_t cont;
  addr_t best_frontier;
  density_t best_density;
  uint32_t best_length;
  uint8_t _padding[20];
};

static_assert(sizeof(ApproxDenseSub_args) == 128,
              "ApproxDenseSub_args must be 1024 bits (widthTask=1024)");

struct vertex_subset_helper_args
{
  addr_t graph;               // 0   CSR vertex table: [neighbor address, degree]
  addr_t degree;              // 8   mutable induced degree D[]
  addr_t frontier;            // 16  current S buffer
  addr_t next_frontier;       // 24  next S buffer
  addr_t nextFChar;           // 32  atomic counter for next S length
  addr_t cont;                // 40
  uint32_t index;             // 48
  uint32_t round;             // 52
  uint32_t vertex_count;      // 56  total vertices in the graph
  uint32_t task_vertex_count; // 60  # vertices in THIS chunk
  density_t threshold;        // 64  2(1+epsilon)D(S)
  // The scheduler's PE-facing AXIS width and backing-queue entry size are both
  // driven by the descriptor's widthTask, which must be a power of two. The real
  // payload is 72 bytes; pad to 128 bytes (1024 bits) so it matches
  // widthTask=1024 in the task descriptor.
  uint8_t _pad[56]; // 72..127
};
static_assert(sizeof(vertex_subset_helper_args) == 128,
              "vertex_subset_helper_args must be 1024 bits (widthTask=1024)");

// Build a lock request beat:
//   tdata[63:0]    = byte address of the slot (tag)
//   tdata[127:64]  = operand / store value (data)
//   tdata[131:128] = opcode
//   tdata[132]     = blocking
//   tdata[134:133] = atomic mode (00 = double-word, 01 = byte, 10 = word)
//   tdata[135]     = float-compare flag: when set, the conditional SET_IF_* ops
//                    order operand vs memory as IEEE-754 floats instead of ints
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
static inline bool lock_resp_success(const lock_resp &resp)
{
#pragma HLS INLINE
  return resp.data(0, 0) != 0;
}

// For a conditional AMU op (SET_IF_GREATER / SET_IF_LESS), true iff the store
// actually happened (the predicate held). For all other ops this mirrors
// lock_resp_success.
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

// The AMU right-justifies the addressed byte into the low bits of the returned
// value, so the previous Visited byte is the low byte of lock_resp_current
// (tdata[79:72]). No shift needed.
static inline uint8_t lock_resp_current_byte(const lock_resp &resp)
{
#pragma HLS INLINE
  return (uint8_t)(lock_resp_current(resp) & 0xFF);
}
