#pragma once

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

using namespace std;


using Addr = uint64_t;


struct pageRankReduce_args {
  // The counter
  uint32_t counter;
  // The arguments
  uint32_t vertex_count;
  Addr adj_list;
  Addr Pcurr;
  Addr Pnext;
  Addr diffs;
  Addr cont;
  uint8_t __padding[16];
};

struct vertex_map_args {
  // The arguments
  Addr Pcurr;
  Addr Pnext;
  Addr diffs;
  Addr adj_list;
  uint64_t return_address;
  uint32_t vertex;
  uint32_t vertex_count;
  uint8_t __padding[16];
};

// Define edgeMap_spawn_next for fib to spawn next new tasks
struct pageRank_spawn_next {
  uint64_t addr;
  pageRankReduce_args  data;
  uint32_t size;
  uint32_t allow;
  uint8_t __padding[48];
};
