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


using addr_t = uint64_t;

struct walk_gen_args {
  uint32_t counter;
  uint32_t num_walks; // Can be maximally vertex count
  uint32_t walk_length; // Should be multiple of 8, maximally 64!
  float stop_probability;
  addr_t global_buffer;
  addr_t cont;
  addr_t graph;
  uint8_t _padding[24];
};

struct walker_args {
  addr_t cont;
  addr_t walk_buffer;
  uint64_t stop_thresh;
  addr_t graph;
  uint32_t walk_length; 
  uint32_t base_vertex;
  uint8_t _padding[24];
};


struct uint32_t_arg_out {
  addr_t addr;
  uint32_t data[8];
  uint32_t size;
  uint32_t allow;
  uint8_t _padding[16];
};


