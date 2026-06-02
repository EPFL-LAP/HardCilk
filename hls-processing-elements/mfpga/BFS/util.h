#pragma once

#include "ap_axi_sdata.h"
#include "ap_int.h"
#include "hls_stream.h"
#include <cstdint>
#include <stddef.h>
#include <stdint.h>

#define MEM_OUT(mem_port, addr, type, value)                                   \
  *((type(*))((uint8_t *)(mem_port) + (addr))) = (value)
#define MEM_IN(mem_port, addr, type)                                           \
  *((type(*))((uint8_t *)(mem_port) + (addr)))

#define MEM_ARR_OUT(mem_port, addr, idx, type, value)                          \
  *((type(*))((uint8_t *)(mem_port) + (addr) + (idx) * sizeof(type))) = (value)
#define MEM_ARR_IN(mem_port, addr, idx, type)                                  \
  *((type(*))((uint8_t *)(mem_port) + (addr) + (idx) * sizeof(type)))

using addr_t = uint64_t;
using Addr = uint64_t;

// 136-bit AXI-Stream lock request/response, matching the lockchisel.LockServer
// wire format (LockServer.ReqWidth == LockServer.RespWidth == 136).
using lock_req = ap_axiu<136, 0, 0, 0>;
using lock_resp = ap_axiu<136, 0, 0, 0>;

// Opcodes accepted by the LockServer (lockchisel.Operation). The operation field
// of a request lives at tdata[131:128].
enum LockOperation : uint8_t {
  LOCK_OP_UNLOCK = 0b0000,
  LOCK_OP_LOCK = 0b0001,
  LOCK_OP_SET_AND_RETURN_CURRENT = 0b0010,
  LOCK_OP_SET_IF_GREATER_AND_RETURN_CURRENT = 0b0011,
  LOCK_OP_SET_IF_LESS_AND_RETURN_CURRENT = 0b0100,
  LOCK_OP_ADD_ONE_RETURN_CURRENT = 0b0101,
  LOCK_OP_UNLOCK_NO_RESPONSE = 0b0111,
};

enum BfsVisitFlags : uint32_t { BFS_VISIT_VERTEX_ALREADY_MARKED = 1 };

// Atomic granularity selector (lockchisel.AtomicMode), carried in tdata[134:133]
// of a lock request. The AMU read-modify-writes only the selected sub-field of
// the 64-bit beat (byte-strobed), so Visited can be a plain byte array.
enum AtomicMode : uint8_t {
  ATOMIC_MODE_DOUBLEWORD = 0b00, // 8 bytes (legacy 64-bit behaviour)
  ATOMIC_MODE_BYTE = 0b01,       // 1 byte
  ATOMIC_MODE_WORD = 0b10,       // 4 bytes
};

// With ATOMIC_MODE_BYTE the AMU test-and-sets a single byte, so every Visited
// slot is one byte (4x less HBM than the 32-bit layout). Addresses stay 64-bit
// on the wire (truncated to the HBM address width).
static const addr_t VISITED_SLOT_BYTES = 1;

struct BFS_args {
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
  uint8_t _padding[40];
};

struct sparse_edgemap_helper_args {
  addr_t graph;
  addr_t distance;
  addr_t visited;
  addr_t frontier;
  addr_t nextFChar;
  addr_t cont;
  uint32_t index;
  uint32_t currentDistance;
  uint32_t vertex_count;
  uint32_t max_depth;
};

// Build a lock request beat:
//   tdata[63:0]    = byte address of the slot (tag)
//   tdata[127:64]  = operand / store value (data)
//   tdata[131:128] = opcode
//   tdata[132]     = blocking
//   tdata[134:133] = atomic mode (00 = double-word, 01 = byte, 10 = word)
//   tdata[135]     = reserved (0)
static inline lock_req make_lock_req(addr_t address, ap_uint<64> value,
                                     LockOperation op, bool blocking,
                                     AtomicMode atomic_mode) {
#pragma HLS INLINE
  lock_req req;
  req.data = 0;
  req.data(63, 0) = (ap_uint<64>)address;
  req.data(127, 64) = value;
  req.data(131, 128) = (uint8_t)op;
  req.data(132, 132) = blocking ? 1 : 0;
  req.data(134, 133) = (uint8_t)atomic_mode;
  req.data(135, 135) = 0;
  req.keep = -1;
  req.strb = -1;
  req.last = 1;
  return req;
}

// Decode a lock response beat. The AMU response path packs Cat(0, data, 1), so:
//   tdata[63:0]    = status (1 == read-modify-write completed)
//   tdata[127:64]  = previous memory contents (the full 64-bit beat that was read)
static inline bool lock_resp_success(const lock_resp &resp) {
#pragma HLS INLINE
  return resp.data(63, 0) != 0;
}

static inline ap_uint<64> lock_resp_current(const lock_resp &resp) {
#pragma HLS INLINE
  return resp.data(127, 64);
}

// The AMU returns the whole 64-bit beat; extract the single byte the request
// addressed (byte-mode atomics).
static inline uint8_t lock_resp_current_byte(const lock_resp &resp,
                                             addr_t address) {
#pragma HLS INLINE
  ap_uint<64> word = lock_resp_current(resp);
  uint32_t shift = ((uint32_t)(address & 0x7)) * 8;
  return (uint8_t)((word >> shift) & 0xFF);
}
