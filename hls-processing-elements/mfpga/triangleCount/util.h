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

// Define the struct for the qsort arguments
struct triangle_args {
  // The counter
  uint32_t counter;
  // The arguments
  uint32_t vertex_count;
  Addr triangle_count_arr;
  Addr adj_list;
  uint64_t cont;
};

struct vertex_map_args {
  // The arguments
  Addr triangle_count_entry;
  Addr adj_list;
  uint64_t return_address;
  uint32_t vertex;
  uint32_t __padding;
};


struct uint32_arg_out {
  Addr addr;
  uint32_t data;
  uint32_t size;
  uint32_t allow;
  uint8_t _padding[12];
};