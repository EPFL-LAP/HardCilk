#pragma once
#include <ap_int.h>
#include <cstdint>
#include <stddef.h>
#include <stdint.h>

#define MEM_OUT(mem_port, addr, type, value) \
  (*((type *)((uint8_t *)(mem_port) + (addr)))) = (value)
#define MEM_IN(mem_port, addr, type) \
  (*((type *)((uint8_t *)(mem_port) + (addr))))

#define MEM_ARR_OUT(mem_port, addr, idx, type, value) \
  (*((type *)((uint8_t *)(mem_port) + (addr) + (idx) * sizeof(type)))) = (value)
#define MEM_ARR_IN(mem_port, addr, idx, type) \
  (*((type *)((uint8_t *)(mem_port) + (addr) + (idx) * sizeof(type))))

using namespace std;

using addr_t = uint64_t;

// Elements of an adjacency list held in an adder's closure per side. One
// memReader fetch fills exactly one window, so this also sets the argument-update
// payload size: ADDER_WINDOW * 4 = 64 bytes = one aligned slot of the closure.
static constexpr uint32_t ADDER_WINDOW = 16;
// Candidate vertices an adder_unit_launcher hands out per round. Argument updates
// are OR-merged, so the partial counts cannot share a field: each adder of the
// batch owns its own 32-bit slot in the launcher's closure.
static constexpr uint32_t LAUNCHER_BATCH = 32;

// The intersection of adj(v) and adj(u), advanced one comparison per task.
//
// Field order is not free here. _counter must sit at bits [31:0] because that is
// where the argument server decrements, and continuation_meta must sit at bits
// [95:64] because the spawnNext write buffer stamps the parent's metadata into
// every task it releases at that fixed offset. Byte 0 is therefore taken, so the
// parent pointer lives further up in _cont and is restored into the done update
// once the merge finishes.
//
// A_data and B_data are each one aligned 64-byte slot, which is exactly what a
// memReader update writes. Updates are OR-merged, so whichever window a fetch
// targets has to be written clear into the closure that parks on it.
struct __attribute__((packed)) counter_continuation {
  uint32_t _counter;              // outstanding memReader updates
  uint32_t offset;                // this adder's count slot in the parent closure
  uint32_t continuation_meta;     // stamped by the write buffer at bits [95:64]
  uint32_t running_partial_count; // matches found so far by this adder
  addr_t _cont;                   // parent adder_unit_launcher closure
  addr_t A;                       // &adj(v)[0]
  addr_t B;                       // &adj(u)[0]
  uint32_t a_cur_ptr;             // next element of A to compare
  uint32_t a_storage_top_pointer; // one past the last element resident in A_data
  uint32_t a_full_len;            // |adj(v)|
  uint32_t b_cur_ptr;
  uint32_t b_storage_top_pointer;
  uint32_t b_full_len;            // |adj(u)|
  uint32_t A_data[ADDER_WINDOW];  // window [a_storage_top_pointer - ADDER_WINDOW, a_storage_top_pointer)
  uint32_t B_data[ADDER_WINDOW];
  uint8_t _padding[64];
};

// One base vertex's driver. Hands out a batch of adders, parks on their partial
// counts, sums them, and goes around again until adj(v) is exhausted.
struct __attribute__((packed)) adder_unit_launcher_continuation {
  uint32_t _counter;               // adders outstanding this round
  uint32_t vertex;                 // v
  addr_t v_neighbors;              // &adj(v)[0]
  addr_t adj_list;                 // graph index base: {ptr, size} per vertex
  uint32_t v_size;                 // |adj(v)|
  uint32_t cursor;                 // next candidate of adj(v) to hand out
  uint32_t running_total;          // batches already summed
  uint32_t _padding0;
  addr_t triangle_count_arr;       // per-vertex {done, count} result array
  uint8_t _padding1[80];
  uint32_t counts[LAUNCHER_BATCH]; // one OR-merged slot per adder of the batch
};

// The root task. Walks every vertex and hands each one to a launcher.
struct __attribute__((packed)) triangle_task {
  addr_t _cont;
  addr_t adj_list;
  addr_t triangle_count_arr;
  uint32_t vertex_count;
  uint8_t _padding[36];
};

struct __attribute__((packed)) memReader_task {
  addr_t _cont;
  uint32_t continuation_meta;
  addr_t start_addr; // byte address of the first element to fetch
  uint32_t offset;   // window slot of the closure to fill
  uint8_t _padding[8];
};

// The adder writes the same packet the memReader reads; the two names only say
// which side of the ring they are on.
using memReader_taskOut = memReader_task;

// Compact cached-notifier packets. Vitis exposes the semantic bits on a
// byte-rounded AXIS port; the generator ignores the zero padding bits above the
// named offset field.
struct __attribute__((packed)) counter_continuation_update {
  addr_t address;
  uint32_t continuation_meta;
  uint32_t payload[ADDER_WINDOW];
  ap_uint<2> offset; // 256-byte closure / 64-byte payload = 4 slots
};

struct __attribute__((packed)) adder_done_continuation_update {
  addr_t address;
  uint32_t continuation_meta;
  uint32_t payload;
  ap_uint<6> offset; // 256-byte closure / 4-byte payload = 64 slots
};

// spawnNext write-buffer packets. The layout mirrors WriteBundleCounter: address,
// continuation line, transfer size, then one allow count per gated taskOut.
//
// A packet whose continuation line carries _counter == 0 is waiting on nothing,
// so the buffer spawns `data` straight down its direct-spawn link instead of
// writing it to the ArgumentServer. That is how an adder goes around again after
// a comparison: nothing was allocated, nothing is written, `data` already holds
// the metadata the buffer would otherwise have stamped in, and allow is 0 because
// there is no memReader task waiting to be released.
struct adder_self_spawn_next {
  addr_t addr;
  counter_continuation data;
  uint32_t size;
  uint32_t allow; // memReaderTaskOut
  uint8_t _padding[240];
};

struct adder_unit_launcher_spawn_next {
  addr_t addr;
  adder_unit_launcher_continuation data;
  uint32_t size;
  uint32_t allow; // adder_taskOut
  uint8_t _padding[240];
};

static_assert(sizeof(counter_continuation) == 256, "adder continuation ABI");
static_assert(offsetof(counter_continuation, continuation_meta) == 8,
              "metadata must land where the write buffer stamps it");
static_assert(offsetof(counter_continuation, A_data) %
                  (ADDER_WINDOW * sizeof(uint32_t)) == 0,
              "A_data must occupy an aligned payload slot");
static_assert(offsetof(counter_continuation, B_data) %
                  (ADDER_WINDOW * sizeof(uint32_t)) == 0,
              "B_data must occupy an aligned payload slot");
static_assert(offsetof(counter_continuation, A_data) /
                      (ADDER_WINDOW * sizeof(uint32_t)) == 1,
              "A_data update-slot ABI");
static_assert(offsetof(counter_continuation, B_data) /
                      (ADDER_WINDOW * sizeof(uint32_t)) == 2,
              "B_data update-slot ABI");

static_assert(sizeof(adder_unit_launcher_continuation) == 256,
              "launcher continuation ABI");
static_assert(offsetof(adder_unit_launcher_continuation, counts) %
                  sizeof(uint32_t) == 0,
              "count slots must be aligned 32-bit updates");
static_assert(offsetof(adder_unit_launcher_continuation, counts) /
                      sizeof(uint32_t) == 32,
              "count update-slot ABI");
static_assert(offsetof(adder_unit_launcher_continuation, counts) /
                          sizeof(uint32_t) + LAUNCHER_BATCH ==
                  sizeof(adder_unit_launcher_continuation) / sizeof(uint32_t),
              "the batch must fill the closure exactly");

static_assert(sizeof(triangle_task) == 64, "root task ABI");
static_assert(sizeof(memReader_task) == 32, "memReader task ABI");
static_assert(offsetof(memReader_task, continuation_meta) == 8,
              "continuation_meta must immediately follow the child address");

static_assert(offsetof(counter_continuation_update, address) == 0,
              "window update address field ABI");
static_assert(offsetof(counter_continuation_update, continuation_meta) == 8,
              "window update metadata field ABI");
static_assert(offsetof(counter_continuation_update, payload) == 12,
              "window update payload field ABI");
static_assert(offsetof(counter_continuation_update, offset) == 76,
              "window update offset field ABI");

static_assert(offsetof(adder_done_continuation_update, address) == 0,
              "count update address field ABI");
static_assert(offsetof(adder_done_continuation_update, continuation_meta) == 8,
              "count update metadata field ABI");
static_assert(offsetof(adder_done_continuation_update, payload) == 12,
              "count update payload field ABI");
static_assert(offsetof(adder_done_continuation_update, offset) == 16,
              "count update offset field ABI");

// wAddr(64) + wData(2048) + size(32) + allow(32), rounded up to the next power
// of two.
static_assert(sizeof(adder_self_spawn_next) == 512, "adder spawnNext packet ABI");
static_assert(sizeof(adder_unit_launcher_spawn_next) == 512,
              "launcher spawnNext packet ABI");
