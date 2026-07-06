#pragma once
#include <cstdint>
#include <stddef.h>
#include <stdint.h>

#define MEM_OUT(mem_port, addr, type, value) \
  *((type(*))((uint8_t *)(mem_port) + (addr))) = (value)
#define MEM_IN(mem_port, addr, type) \
  *((type(*))((uint8_t *)(mem_port) + (addr)))

#define MEM_ARR_OUT(mem_port, addr, idx, type, value) \
  *((type(*))((uint8_t *)(mem_port) + (addr) + (idx) * sizeof(type))) = (value)
#define MEM_ARR_IN(mem_port, addr, idx, type) \
  *((type(*))((uint8_t *)(mem_port) + (addr) + (idx) * sizeof(type)))

#define MEM_STRUCT(mem_port, str, str_type, field) \
    (((str_type*)((uint8_t*)(mem_port) + (str)))->field)
#define MEM_STRUCT_ARR_OUT(mem_port, str, str_type, field, idx, type, value) \
  *((type *)((uint8_t *)(mem_port) + (str) + offsetof(str_type, field) +     \
             (idx) * sizeof(type))) = (value)
#define MEM_STRUCT_ARR_IN(mem_port, str, str_type, field, idx, type)         \
  *((type *)((uint8_t *)(mem_port) + (str) + offsetof(str_type, field) +     \
             (idx) * sizeof(type)))

using namespace std;

using addr_t = uint64_t;

struct __attribute__((packed))spawnerFunction_cont0_task {
  uint32_t _counter;
  addr_t cont;
  uint8_t _padding[4];
};

struct spawnerFunction_cont0_spawn_next {
  addr_t addr;
  spawnerFunction_cont0_task data;
  uint32_t size;
  uint32_t allow;
};

struct __attribute__((packed))spawnerFunction_task {
  addr_t cont;
  uint32_t vertexCount;
  addr_t pGraph;
  addr_t triangleCounts;
  uint8_t _padding[4];
};

struct __attribute__((packed))applyFn_task {
  addr_t cont;
  uint32_t u;
  addr_t pGraph;
  addr_t triangleCounts;
  uint8_t _padding[4];
};

struct __attribute__((packed)) uint32_t_arg_out {
  addr_t addr;
  uint32_t data;
  uint32_t size;
  uint32_t allow;
  uint8_t _padding[12];
};

