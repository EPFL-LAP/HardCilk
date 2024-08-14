#pragma once

#include <cstdint>
#include <stdint.h>
#include "hls_stream.h"
#include <stddef.h>

#define MEM_OUT(mem_port, addr, type, value)  *((type (*)) ((uint8_t *) (mem_port) + (addr))) = (value)
#define MEM_IN(mem_port, addr, type)  *((type (*)) ((uint8_t *) (mem_port) + (addr)))


using namespace std;

// Define the struct for the fib arguments
struct fib_args {
    // The continuation address
    uint64_t cont;
    // The n value
    uint64_t n;
    //uint32_t Padding = 0;
};

// Define the struct for the sum arguments
struct sum_args {
    // The counter
    uint32_t join_counter;

    // The first summand
    int32_t x;

    // The second summand
    int32_t y;

    // Padding 1
    uint32_t pad1 = 0;

    // Padding 2
    uint64_t pad2 = 0;

    // The continuation address
    uint64_t cont;
};

void fib(hls::stream<fib_args> &taskIn, 
        hls::stream<fib_args> &taskOut, 
        hls::stream<uint64_t> &argOut, 
        hls::stream<uint64_t> &closureIn, 
        void *mem) ;

void sum(hls::stream<sum_args> &taskIn, 
        hls::stream<uint64_t> &argOut,
        void * mem);