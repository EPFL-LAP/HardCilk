#pragma once
// Host-side copy of the fullTriangleCountDecoupled ABI. Must stay byte-identical
// to hls-processing-elements/mfpga/fullTriangleCountDecoupled/util.h; the parts
// that need <ap_int.h> (the argument-update packets, whose `offset` field is an
// ap_uint, and the burst_maxi beat typedefs) are omitted because the host never
// constructs one.
//
// Everything else is mirrored verbatim, INCLUDING the static_asserts. They are the
// only thing that catches this file drifting from the kernel, because the host
// reads no field of these structs -- only sizeof() and the two batch constants --
// so a mismatch mis-sizes the closure pool instead of failing. Change the kernel's
// copy and this one in the same commit.
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
// payload size: ADDER_WINDOW * 4 = 32 bytes = one aligned slot of the closure.
static constexpr uint32_t ADDER_WINDOW = 8;
// Candidate vertices an adder_unit_launcher hands out per round. Argument updates
// are OR-merged, so the partial counts cannot share a field: each adder of the
// batch owns its own 32-bit slot in the launcher's closure.
static constexpr uint32_t LAUNCHER_BATCH = 16;

// The intersection of adj(v) and adj(u), advanced one comparison per task.
//
// Field order is not free here. _counter must sit at bits [31:0] because that is
// where the argument server decrements, and continuation_meta must sit at bits
// [95:64] because the spawnNext write buffer stamps the parent's metadata into
// every task it releases at that fixed offset. Byte 0 is therefore taken, so the
// parent pointer lives further up in _cont and is restored into the done update
// once the merge finishes.
//
// A_data and B_data are each one aligned 32-byte slot, which is exactly what a
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
};

// One base vertex's driver. Hands out a batch of adders, parks on their partial
// counts, sums them, and goes around again until adj(v) is exhausted.
//
// The launcher is a child continuation of the vertex_writeback closure triangle
// parks for this vertex, so it obeys the same two pinned offsets that
// counter_continuation does: _counter at bits [31:0] for the argument server,
// continuation_meta at bits [95:64] for the spawnNext write buffer.
//
// There is no `offset` field, unlike counter_continuation. That one exists
// because LAUNCHER_BATCH adders share one launcher closure and each has to be
// told which counts[] slot is its own. Exactly one launcher reports into a
// writeback closure, so its slot is the compile-time constant offsetof(count)/4
// and the launcher stamps it into the update rather than carrying it. The
// writeback closure also carries the vertex and the result array, so the launcher
// never has to know where the answer lands.
struct __attribute__((packed)) adder_unit_launcher_continuation {
  uint32_t _counter;               // adders outstanding this round
  uint32_t v_size;                 // |adj(v)|
  uint32_t continuation_meta;      // stamped by the write buffer at bits [95:64]
  uint32_t cursor;                 // next candidate of adj(v) to hand out
  addr_t _cont;                    // parent vertex_writeback closure
  addr_t v_neighbors;              // &adj(v)[0]
  addr_t adj_list;                 // graph index base: {ptr, size} per vertex
  uint32_t running_total;          // batches already summed
  uint8_t _padding[20];
  uint32_t counts[LAUNCHER_BATCH]; // one OR-merged slot per adder of the batch
};

// One vertex's answer, and the admission token for the whole run.
//
// triangle takes one of these before it spawns a vertex's launcher, so the size
// of this pool -- and nothing else -- caps how many vertices are in flight. The
// cap is self-enforcing: the pool only refills when a vertex retires, and
// triangle blocking on closureIn cannot stall anything that would free a
// closure, because triangle consumes nothing from downstream.
//
// count is the single OR-merged slot the launcher writes once adj(v) is
// exhausted. It sits at word 3, so a two-bit offset addresses the whole line.
struct __attribute__((packed)) vertex_writeback_continuation {
  uint32_t _counter;          // one launcher outstanding
  uint32_t _padding0;
  uint32_t continuation_meta; // stamped by the write buffer at bits [95:64]
  uint32_t count;             // OR-merged: this vertex's wedge closures
  addr_t triangle_count_arr;  // per-vertex {done, count} result array
  uint32_t vertex;            // v
  uint32_t _padding1;
  // Padded to the same 128-byte line as the other two continuations, which is what
  // keeps this task's notifier masters at the same 1024-bit AXI shape as the
  // adder's and the launcher's (memoryAxiDataWidth follows continuationSize). At
  // 256 bits they become two new shape classes, the HBM allocator runs out of
  // shape-aware ports, and every mixed port falls to the id-collapse path.
  uint8_t _padding2[96];
};

// One entry of the graph index. The two fields are adjacent, so reading them as
// a pair is one 16-byte bus access instead of two 8-byte ones -- which also
// keeps a dependent address computation off the critical path, since nothing
// has to wait on the first field to issue the second read.
struct __attribute__((packed)) adj_entry {
  addr_t neighbors; // &adj(u)[0]
  uint64_t size;    // |adj(u)|
};

// The root task. Walks every vertex and hands each one to a launcher.
// first_vertex/vertex_count is a half-open range, not just a count: the host
// slices a graph whose closure demand exceeds the pool into several runs over
// disjoint vertex ranges, and adj_list is indexed by GLOBAL vertex id (candidate
// lookups use ids from anywhere in the graph), so the range cannot be expressed
// by shifting the base pointer.
struct __attribute__((packed)) triangle_task {
  addr_t _cont;
  addr_t adj_list;
  addr_t triangle_count_arr;
  uint32_t first_vertex;
  uint32_t vertex_count;
  uint8_t _padding[32];
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

// spawnNext write-buffer packets. The layout mirrors WriteBundleCounter: address,
// continuation line, transfer size (log2 bytes), then one allow count per gated
// taskOutGlobal. The buffer writes the line to the ArgumentServer and, once that
// write is acknowledged, releases `allow` tasks from the matching stream -- which
// is what keeps a child from reaching a scheduler before the closure it points at
// exists. A re-entry that allocates nothing needs none of this and goes out the
// PE's spawnNextLocal port instead, bypassing the buffer entirely.
//
// The host never builds one of these. They are mirrored anyway because they
// embed the continuation structs, so their static_asserts below fail the host
// build if a closure ever changes size without this file being updated.
struct adder_self_spawn_next {
  addr_t addr;
  counter_continuation data;
  uint32_t size;
  uint32_t allow; // taskOutGlobal1: the memReader fetch
  uint8_t _padding[240];
};

struct adder_unit_launcher_spawn_next {
  addr_t addr;
  adder_unit_launcher_continuation data;
  uint32_t size;
  uint32_t allow; // taskOutGlobal1: this round's adders
  uint8_t _padding[240];
};

struct triangle_spawn_next {
  addr_t addr;
  vertex_writeback_continuation data;
  uint32_t size;
  uint32_t allow; // taskOutGlobal: this vertex's launcher
  uint8_t _padding[112];
};

static_assert(sizeof(counter_continuation) == 128, "adder continuation ABI");
static_assert(offsetof(counter_continuation, continuation_meta) == 8,
              "metadata must land where the write buffer stamps it");
static_assert(offsetof(counter_continuation, A_data) %
                  (ADDER_WINDOW * sizeof(uint32_t)) == 0,
              "A_data must occupy an aligned payload slot");
static_assert(offsetof(counter_continuation, B_data) %
                  (ADDER_WINDOW * sizeof(uint32_t)) == 0,
              "B_data must occupy an aligned payload slot");
static_assert(offsetof(counter_continuation, A_data) /
                      (ADDER_WINDOW * sizeof(uint32_t)) == 2,
              "A_data update-slot ABI");
static_assert(offsetof(counter_continuation, B_data) /
                      (ADDER_WINDOW * sizeof(uint32_t)) == 3,
              "B_data update-slot ABI");

static_assert(sizeof(adder_unit_launcher_continuation) == 128,
              "launcher continuation ABI");
static_assert(offsetof(adder_unit_launcher_continuation, continuation_meta) == 8,
              "metadata must land where the write buffer stamps it");
static_assert(offsetof(adder_unit_launcher_continuation, counts) %
                  sizeof(uint32_t) == 0,
              "count slots must be aligned 32-bit updates");
static_assert(offsetof(adder_unit_launcher_continuation, counts) /
                      sizeof(uint32_t) == 16,
              "count update-slot ABI");
static_assert(offsetof(adder_unit_launcher_continuation, counts) /
                          sizeof(uint32_t) + LAUNCHER_BATCH ==
                  sizeof(adder_unit_launcher_continuation) / sizeof(uint32_t),
              "the batch must fill the closure exactly");

static_assert(sizeof(vertex_writeback_continuation) == 128,
              "writeback continuation ABI");
static_assert(offsetof(vertex_writeback_continuation, continuation_meta) == 8,
              "metadata must land where the write buffer stamps it");
static_assert(offsetof(vertex_writeback_continuation, count) %
                  sizeof(uint32_t) == 0,
              "the count slot must be an aligned 32-bit update");
static_assert(offsetof(vertex_writeback_continuation, count) /
                      sizeof(uint32_t) == 3,
              "count update-slot ABI");

static_assert(sizeof(adj_entry) == 16, "graph index entry ABI");
static_assert(sizeof(triangle_task) == 64, "root task ABI");
static_assert(sizeof(memReader_task) == 32, "memReader task ABI");
static_assert(offsetof(memReader_task, continuation_meta) == 8,
              "continuation_meta must immediately follow the child address");

// wAddr(64) + wData(2048) + size(32) + allow(32), rounded up to the next power
// of two.
static_assert(sizeof(adder_self_spawn_next) == 384, "adder spawnNext packet ABI");
static_assert(sizeof(adder_unit_launcher_spawn_next) == 384,
              "launcher spawnNext packet ABI");
static_assert(sizeof(triangle_spawn_next) == 256,
              "triangle spawnNext packet ABI");
