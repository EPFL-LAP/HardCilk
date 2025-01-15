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

struct Pair32 {
  uint32_t first;
  uint32_t second;
};

using Addr = uint64_t;

// Define the struct for the qsort arguments
struct edgemap_args {
  // The counter
  uint32_t counter;
  // The arguments
  uint32_t g_size;
  Addr g_edges;
  Addr f;
  Addr flag_visited;
  Addr d;
  Addr sets;
  uint32_t size;
  uint16_t round;
  uint8_t is_sync;
  uint8_t __padding;
  uint64_t cont;
};

struct task_args {
  // The arguments
  uint32_t __g_size;
  uint32_t vertex;
  uint16_t round;
  uint16_t __padding;
  uint32_t __padding0;
  Addr g_edges;
  Addr flag_visited;
  Addr d;
  Addr set;
  uint64_t cont;
  uint8_t __padding1[8];
};

// Define fib_spawn_next for fib to spawn next new tasks
struct edgeMap_spawn_next {
  uint64_t addr;
  edgemap_args  data;
  uint32_t size;
  uint32_t allow;
  uint8_t __padding[48];
};

struct task_arg_out {
  uint64_t addr;
  uint64_t data;
  uint32_t size;
  uint8_t allow;
  uint8_t __padding[8];
};
