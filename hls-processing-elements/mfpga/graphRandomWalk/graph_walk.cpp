#include <cstdint>
#include <stdio.h>
#include <stdint.h>
#include <algorithm>
#include <sys/types.h>
#include "util.h"


#define MAX_SUPPORTED_SIZE 64


struct RngState {
    uint64_t s;
};

static inline uint64_t xorshift64(uint64_t &x) {
    x ^= x >> 12;
    x ^= x << 25;
    x ^= x >> 27;
    return x * 2685821657736338717ULL;
}

static inline void seed_rng(RngState &rng, uint64_t seed) {
    rng.s = seed + 0x9E3779B97F4A7C15ULL;
}

/************ Uniform integer without modulo bias ************/
static inline uint32_t uniform_u32(RngState &rng, uint32_t n) {
    uint64_t x;
    uint64_t limit = (UINT64_MAX / n) * n;
    do {
        x = xorshift64(rng.s);
    } while (x >= limit);
    return x % n;
}


void walker(void * mem_0,
                void * mem_1,
                hls::stream<walker_args> &taskIn,
                hls::stream<uint64_t> &argOut,
                hls::stream<uint32_t_arg_out> &argDataOut){

#pragma HLS INTERFACE ap_ctrl_none port=return

#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = argOut
#pragma HLS INTERFACE mode = axis port = argDataOut

#pragma HLS INTERFACE mode = m_axi port = mem_0 bundle=gmem channel=0 latency=32 num_write_outstanding=1 num_read_outstanding=64 max_read_burst_length=16 max_widen_bitwidth=256
#pragma HLS INTERFACE mode = m_axi port = mem_1 bundle=gmem channel=1 latency=32 num_write_outstanding=1 num_read_outstanding=64 max_read_burst_length=16 max_widen_bitwidth=256

uint32_t buffer[MAX_SUPPORTED_SIZE];

#pragma HLS ARRAY_PARTITION variable=buffer cyclic factor=8


//#pragma HLS cache port=mem_1 lines=2 depth=32

    auto task = taskIn.read();
    RngState rng;
    seed_rng(rng, 0xdeadbeef ^ (uint64_t)task.base_vertex);

    // Send to write buffer
    uint32_t add_to_visited = task.base_vertex;
    bool stop = false;


    for (int i = 0; i < MAX_SUPPORTED_SIZE; i++) {
        #pragma HLS UNROLL
        buffer[i] = -1;
    }

    int buffer_valid_count = 0;

    for(int step = 0; step <= task.walk_length; ++step){
        if (xorshift64(rng.s) < task.stop_thresh || step == task.walk_length) {
            stop = true;
        }

        buffer[step] = add_to_visited;
        buffer_valid_count+=1;

        if(stop){
          break;
        } 
  
        addr_t v_neighbours = MEM_IN(mem_0, task.graph + (add_to_visited << 4), addr_t);
        uint32_t degree = MEM_IN(mem_0, task.graph + (add_to_visited << 4) + 8, uint64_t);

        if(degree == 0){
          break;
        }        
        
        // Pick uniform neighbor
        uint32_t k = uniform_u32(rng, degree);
    
        // Read vertex k chosen by the random uniform function
        add_to_visited = MEM_ARR_IN(mem_1, v_neighbours, k, uint32_t);
    }    

    for(int i = 0; i < buffer_valid_count; i+=8){
      #pragma HLS PIPELINE
      uint32_t_arg_out toMemArg;
 
      // Write 8 values at a time to the memory!
      for(int j = 0; j < 8; j++){
          #pragma HLS UNROLL
          toMemArg.data[j] = buffer[i+j];
      }
      uint32_t allow = 0;
      if(i + 8 >=  buffer_valid_count){
        allow = 1;
      }

      toMemArg.addr = task.walk_buffer + (i * 4);
      toMemArg.allow = allow;
      
      //toMemArg.data = data_to_write;

      toMemArg.size = 5;
      argDataOut.write(toMemArg);
    }

    // Write to the continuation
    argOut.write(task.cont);
}


void walk_gen( 
  hls::stream<walker_args> &taskOutGlobal,
  hls::stream<walk_gen_args> &taskIn
  ){

  #pragma HLS INTERFACE ap_ctrl_none port=return

  #pragma HLS INTERFACE mode = axis port = taskIn
  #pragma HLS INTERFACE mode = axis port = taskOutGlobal
 
  // read first task for init
  auto base_task = taskIn.read();
  //const uint64_t stop_thresh = (uint64_t)(base_task.stop_probability * (double)UINT64_MAX);
  uint64_t stop_thresh = (uint64_t)(base_task.stop_probability * (double)UINT64_MAX);


  // spawn the vertex_map tasks
  for(int i = 0; i < base_task.num_walks; i++){
    #pragma HLS PIPELINE II=1
    walker_args walker_task;
    walker_task.cont = base_task.cont;
    walker_task.stop_thresh = stop_thresh;
    walker_task.walk_length = base_task.walk_length;
    walker_task.walk_buffer = (base_task.global_buffer + i * 4 * base_task.walk_length); // 4 is size of entry in the walk_buffer
    walker_task.base_vertex = i;
    walker_task.graph = base_task.graph;
    taskOutGlobal.write(walker_task);
  }  

}