#pragma once
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

struct __attribute__((packed))whileLoopMain_reentry0_cont0_task {
  uint32_t _counter;
  addr_t _cont;
  addr_t A;
  addr_t B;
  addr_t count;
  uint32_t size;
  uint32_t i;
  uint32_t j;
  uint32_t a_i;
  uint32_t b_j;
  uint8_t _padding[8];
};

struct whileLoopMain_reentry0_cont0_spawn_next {
  addr_t addr;
  whileLoopMain_reentry0_cont0_task data;
  uint32_t size;
  uint32_t allow;
  uint8_t _padding[48];
};

struct __attribute__((packed))whileLoopMain_exit0_task {
  addr_t _cont;
};

struct __attribute__((packed))memReader_task {
  addr_t _cont;
  addr_t mem;
  uint32_t idx;
  uint8_t _padding[12];
};

struct __attribute__((packed)) uint32_t_arg_out {
  addr_t addr;
  uint32_t data;
  uint32_t size;
  uint32_t allow;
  uint8_t _padding[12];
};

struct __attribute__((packed))whileLoopMain_task {
  addr_t _cont;
  addr_t A;
  addr_t B;
  addr_t count;
  uint32_t size;
  uint8_t _padding[28];
};

struct __attribute__((packed))whileLoopMain_reentry0_task {
  addr_t _cont;
  addr_t A;
  addr_t B;
  addr_t count;
  uint32_t size;
  uint32_t i;
  uint32_t j;
  uint32_t a_i;
  uint32_t b_j;
  uint8_t _padding[12];
};
