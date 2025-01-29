#include "def.h"
#include <bits/types/cookie_io_functions_t.h>
#include <cstddef>
#include <cstdint>
#include <etc/autopilot_ssdm_op.h>
#include <string.h>
#include <stdio.h>
#include <cmath>
#include <sys/types.h>
#include <ap_axi_sdata.h>
#include <algorithm>
#include <type_traits>

#define LOCAL_BUFFER_SIZE 64 // must be a power of 2
#define epsilon (1e-9)
#define damping 0.85


double reduceDiffs(void * mem, Addr diffs, uint32_t vertex_count)
{

  double partial_sums [LOCAL_BUFFER_SIZE];

  for(uint32_t j = 0; (j < LOCAL_BUFFER_SIZE); j++){
      partial_sums[j] = 0;
  }


  for(uint32_t i = 0; i < vertex_count; i+=1){
    for(uint32_t j = 0; (j < LOCAL_BUFFER_SIZE) && (i+j) < vertex_count; j++){
      #pragma HLS PIPELINE II = 1
      partial_sums[j] += MEM_ARR_IN(mem, diffs, i + j, double);
    }
    if(partial_sums[0] > epsilon){
      return epsilon + 1;
    }
  }

  double sum = 0;
  for(uint32_t j = 0; (j < LOCAL_BUFFER_SIZE-4); j+=4){
    #pragma HLS UNROLL factor=4
    double sum0 = partial_sums[j] + partial_sums[j+1];
    double sum1 = partial_sums[j+2] + partial_sums[j+4];    
    sum += sum0+sum1;
  }

  return sum;
}

void pageRankReduce(void *mem,
             hls::stream<pageRankReduce_args> &taskIn,
             hls::stream<vertex_map_args> &taskOutGlobal,
             hls::stream<uint64_t> &argOut,
             hls::stream<pageRank_spawn_next> &spawnNext,
             hls::stream<uint64_t> &closureIn
             ) {

#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = taskOutGlobal
#pragma HLS INTERFACE mode = axis port = spawnNext
#pragma HLS INTERFACE mode = axis port = closureIn
#pragma HLS INTERFACE mode = axis port = argOut
#pragma HLS INTERFACE mode = m_axi port = mem bundle=gmem
#pragma HLS PIPELINE off

  auto task = taskIn.read();
  auto diffs = task.diffs; 
  auto vertex_count = task.vertex_count;

  double error = 0;

  error = reduceDiffs(mem, diffs, vertex_count);
  
  if(error > epsilon){
    std::swap(task.Pcurr, task.Pnext);
    // Create a continuation with the number of verticies as counter
    task.counter = vertex_count;
    pageRank_spawn_next sn0;
    sn0.addr = closureIn.read();
    sn0.data = task;
    sn0.allow = vertex_count;
    sn0.size = 6; //TODO

    spawnNext.write(sn0);

    for(uint32_t i = 0; i < vertex_count; i++){
      #pragma HLS PIPELINE
      vertex_map_args vma0;
      vma0.Pcurr = task.Pcurr;
      vma0.Pnext = task.Pnext;
      vma0.return_address = sn0.addr;
      vma0.vertex = i;
      vma0.vertex_count = vertex_count;
      vma0.adj_list = task.adj_list;
      vma0.diffs = diffs;
      taskOutGlobal.write(vma0);
    }

  } else {
    // return
    argOut.write(task.cont);
  }
  
} 


void vertex_map(void *mem_0,
              void * mem_1,
             hls::stream<vertex_map_args> &taskIn,
             hls::stream<uint64_t> &argOut){
#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = argOut
#pragma HLS INTERFACE mode = m_axi port = mem_0 bundle=gmem
#pragma HLS INTERFACE mode = m_axi port = mem_1 bundle=gmem
#pragma HLS PIPELINE off

  auto task = taskIn.read();

  // Get contributions
  double sum = 0;
  Addr v_neighbours = MEM_ARR_IN(mem_0, task.adj_list, task.vertex, Addr);
  uint32_t v_neighbours_size = MEM_ARR_IN(mem_0, v_neighbours, 0, uint32_t);

  double contributions[LOCAL_BUFFER_SIZE];
  #pragma HLS ARRAY_PARTITION variable=contributions complete

  // uint32_t u_neighbours_local[LOCAL_BUFFER_SIZE];
  // uint32_t degree_u_local[LOCAL_BUFFER_SIZE];
  // uint32_t Pcurr_u_local[LOCAL_BUFFER_SIZE];

  for(uint32_t i = 0; i < v_neighbours_size; i+=LOCAL_BUFFER_SIZE){
    #pragma HLS PIPELINE

    for(uint32_t j = 0; j < LOCAL_BUFFER_SIZE && j+i < v_neighbours_size; j++){
      #pragma HLS PIPELINE
      uint32_t u = MEM_ARR_IN(mem_0, v_neighbours, j + i + 1, uint32_t);
      Addr u_neighbours = MEM_ARR_IN(mem_0, task.adj_list, u, Addr);
      uint32_t degree_u = MEM_ARR_IN(mem_0, u_neighbours, 0, uint32_t);
      double Pcurr_u = MEM_ARR_IN(mem_1, task.Pcurr, u, double);
      //double contribution = Pcurr_u / degree_u;
      contributions[j] = Pcurr_u / degree_u;
    }

    for(int j = 0; j < LOCAL_BUFFER_SIZE && j+i < v_neighbours_size; j++){
      #pragma HLS PIPELINE
      sum += contributions[j];
    }    
    
  }

  double Pnext_v = (1 - damping) / task.vertex_count + damping * sum;
  double Pcurrent_v = MEM_ARR_IN(mem_0, task.Pcurr, task.vertex, double);
  double diffs_v = std::abs(Pnext_v - Pcurrent_v);

  MEM_ARR_OUT(mem_1, task.Pnext, task.vertex, double, Pnext_v);
  MEM_ARR_OUT(mem_1, task.diffs, task.vertex, double, diffs_v);
  
  volatile double val = MEM_ARR_IN(mem_1, task.diffs, task.vertex, double);

  while(val != diffs_v){
    val = MEM_ARR_IN(mem_1, task.diffs, task.vertex, double);
  }
  if(val == diffs_v){
      argOut.write(task.return_address);
  }
  // TODO: Resynthize
}

