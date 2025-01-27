#include "def.h"
#include <cstddef>
#include <cstdint>
#include <etc/autopilot_ssdm_op.h>
#include <string.h>
#include <stdio.h>
#include <cmath>
#include <sys/types.h>
#include <ap_axi_sdata.h>
#include <algorithm>

#define LOCAL_BUFFERS_SIZE 64

uint32_t count_intersection(void * mem, Addr v_neighbors, Addr u_neighbors, uint32_t v_size, uint32_t u_size){
  #pragma HLS PIPELINE II = 1

  // Find the intersection of the neighbours of v and u
  uint32_t count = 0;

  // Intersection of ordered sets! O(n)
  uint32_t v_buffer[LOCAL_BUFFERS_SIZE];
  uint32_t u_buffer[LOCAL_BUFFERS_SIZE];
  uint32_t v_pointer = 0;
  uint32_t u_pointer = 0;  

  v_neighbors += sizeof(uint32_t);
  u_neighbors += sizeof(uint32_t);  


  while((u_pointer < u_size && v_pointer < v_size)){

    for(int j = 0; (j < LOCAL_BUFFERS_SIZE) && ((j+v_pointer) < v_size); j++){
      #pragma HLS PIPELINE II=1
      v_buffer[j] = MEM_ARR_IN(mem, v_neighbors, v_pointer + j, uint32_t); 
    }

    for(int j = 0; (j < LOCAL_BUFFERS_SIZE) && ((j+u_pointer) < u_size); j++){
      #pragma HLS PIPELINE II=1
      u_buffer[j] = MEM_ARR_IN(mem, u_neighbors, u_pointer + j, uint32_t);
    }

    uint32_t v_local_pointer = 0;
    uint32_t u_local_pointer = 0;

    while(v_pointer < v_size && v_local_pointer < LOCAL_BUFFERS_SIZE  && u_pointer < u_size && u_local_pointer < LOCAL_BUFFERS_SIZE){
      uint32_t entry_v = v_buffer[v_local_pointer];
      uint32_t entry_u = u_buffer[u_local_pointer];
      if(entry_v < entry_u){
        v_pointer+=1;
        v_local_pointer+=1;
      } else if (entry_v > entry_u){
        u_pointer+=1;
        u_local_pointer+=1;
      } else {
        count += 1;
        v_pointer+=1;
        v_local_pointer+=1;
        u_pointer+=1;
        u_local_pointer+=1;
      }
    }
  }

  // for(int i = 0; i < v_size; i++){
  //   #pragma HLS PIPELINE II =1
  //   uint32_t a = MEM_ARR_IN(mem, v_neighbors, i + 1, uint32_t);
  //   uint32_t partial_count = 0; 
  //   for(int j = 0; j < u_size; j++){
  //     #pragma HLS PIPELINE II=1
  //     uint32_t b = MEM_ARR_IN(mem, u_neighbors, j + 1, uint32_t);
  //     partial_count += (1 & (a == b));
  //   }
  //   count += partial_count;
  // }

  return count;
}



void vertex_map(void *mem, 
                hls::stream<vertex_map_args> &taskIn,
                hls::stream<uint64_t> &argOut) {
#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = argOut
#pragma HLS INTERFACE mode = m_axi port = mem bundle=gmem

#pragma HLS PIPELINE  

  auto task = taskIn.read();
  Addr adj_list = task.adj_list;
  uint32_t v = task.vertex;
  Addr triangle_count_entry = task.triangle_count_entry;

  uint32_t count = 0;

  // Iterate over the neighbours of the vertex
  Addr v_neighbors = MEM_ARR_IN(mem, adj_list, v, Addr);
  uint32_t v_size_neighbors = MEM_ARR_IN(mem, v_neighbors, 0, uint32_t);

  // uint32_t partial_counts[LOCAL_BUFFERS_SIZE];
  // uint32_t u_list[LOCAL_BUFFERS_SIZE];
  // uint32_t u_size_neighbors_list[LOCAL_BUFFERS_SIZE];

  // Find the intersections of u neighbours and v neighbours
  for (uint32_t i = 0; i < v_size_neighbors; i+=1) {
    // for(int j = 0; j < LOCAL_BUFFERS_SIZE && (i+j) < v_size_neighbors; i++){
    //   u_list[j] = MEM_ARR_IN(mem, v_neighbors, i + j + 1, uint32_t);
    //   u_size_neighbors_list[j] = MEM_ARR_IN(mem, u_list[j], 0, uint32_t);
    // }

    // for(int j = 0; j < LOCAL_BUFFERS_SIZE && (i+j) < v_size_neighbors; i++){
    //   partial_counts[j] = count_intersection(mem, v_neighbors, u_list[j], v_size_neighbors, u_size_neighbors_list[j]);
    // }

    // for(int j = 0; j < LOCAL_BUFFERS_SIZE && (i+j) < v_size_neighbors; i++){
    //   count += partial_counts[j];
    // }
    
    uint32_t u = MEM_ARR_IN(mem, v_neighbors, i + 1, uint32_t);
    Addr u_neighbors = MEM_ARR_IN(mem, adj_list, u, Addr);
    uint32_t u_size_neighbors = MEM_ARR_IN(mem, u_neighbors, 0, uint32_t);

    count += count_intersection(mem, v_neighbors, u_neighbors, v_size_neighbors, u_size_neighbors);
    
  }


  MEM_ARR_OUT(mem, triangle_count_entry, 0, uint32_t, count);  

  volatile uint32_t readBackSync = count;

  while(MEM_ARR_IN(mem, triangle_count_entry, 0, uint32_t) != readBackSync){

  }
  argOut.write(task.return_address);
}

void triangle(void *mem,
             hls::stream<triangle_args> &taskIn,
             hls::stream<vertex_map_args> &taskOutGlobal
             ) {

#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = taskOutGlobal
#pragma HLS INTERFACE mode = m_axi port = mem bundle=gmem

#pragma HLS PIPELINE off

  auto task = taskIn.read();

  Addr triangle_count_arr = task.triangle_count_arr;
  Addr adj_list = task.adj_list;
  uint32_t vertex_count = task.vertex_count;

  for(int i = 0; i < vertex_count; i++){
    #pragma HLS PIPELINE II=1
    vertex_map_args new_task;
    new_task.adj_list = adj_list;
    new_task.vertex = i;
    new_task.triangle_count_entry = triangle_count_arr + (i * sizeof(uint32_t));
    new_task.return_address = task.cont;
    taskOutGlobal.write(new_task);
  }

}
