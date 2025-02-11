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
#include "hls_stream.h"
#include "hls_print.h"


void readVertex(void * mem0,
                void * mem1, 
                void * mem2, 
                Addr v_neighbors, 
                uint32_t v_size, 
                Addr adj_list, 
                hls::stream<Addr>& u_neighbors_fifo, 
                hls::stream<uint32_t>& u_size_fifo0, 
                hls::stream<uint32_t>& u_size_fifo1,
                hls::stream<uint32_t>& u_size_fifo2){
                  
  
  for(int i = 0; i < v_size; i++){
    #pragma HLS PIPELINE II =1
    uint32_t u = MEM_ARR_IN(mem0, v_neighbors, i + 1, uint32_t);
    Addr u_neighbors = MEM_ARR_IN(mem1, adj_list, u, Addr);
    uint32_t u_size =  MEM_ARR_IN(mem2, u_neighbors, 0, uint32_t);
    u_neighbors_fifo.write(u_neighbors);
    u_size_fifo0.write(u_size);
    u_size_fifo1.write(u_size);
    u_size_fifo2.write(u_size);
  }
}

void readMemV(hls::stream<uint32_t> &elements_v,
              void * mem_v,
              Addr v_neighbors,
              uint32_t v_size,
              hls::stream<uint32_t> & u_size_fifo
      ){
        for(int j = 0; j < v_size; j++){
          #pragma HLS PIPELINE
          uint32_t u_size = u_size_fifo.read();
          if(u_size > 0){
            for(int i = 0; i < v_size; i++){
              #pragma HLS PIPELINE
              uint32_t read_v = MEM_ARR_IN(mem_v, v_neighbors, i + 1, uint32_t);
              elements_v.write(read_v);  
            }
          }
        }
}

void readMemU(hls::stream<uint32_t> &elements_u,
              void * mem_u,
              uint32_t v_size,
              hls::stream<Addr> & u_neighbors_fifo,
              hls::stream<uint32_t> & u_size_fifo
      ){
        for(int j = 0; j < v_size; j++){
          #pragma HLS PIPELINE
          uint32_t u_size = u_size_fifo.read();
          Addr u_neighbors = u_neighbors_fifo.read();
          if(u_size > 0){
            for(int i = 0; i < u_size; i++){
              #pragma HLS PIPELINE
              uint32_t read_u = MEM_ARR_IN(mem_u, u_neighbors, i + 1, uint32_t);
              elements_u.write(read_u);  
            }
          }
        }
}




void processData( hls::stream<uint32_t> &elements_v, 
              hls::stream<uint32_t> &elements_u,
              uint32_t v_size,
              hls::stream<uint32_t> &u_size_fifo,
              uint32_t &count){
  #pragma HLS PIPELINE II=1


  uint32_t entry_v, entry_u;

  for(int j = 0; j < v_size; j++){
    //hls::print("loop %d\n", j);
    //hls::print("vsize %d\n", v_size);
    

    uint32_t u_size = u_size_fifo.read();
    uint32_t v_pointer = 0;
    uint32_t u_pointer = 0;  

    
    if(u_size > 0){
      entry_v = elements_v.read();
      entry_u = elements_u.read();

      uint32_t v_popped = 1;
      uint32_t u_popped = 1;

      while(v_pointer < v_size && u_pointer < u_size){
          #pragma HLS PIPELINE II=1
          //hls::print("Entered while loop v_pointer: %d\n", v_pointer);
          //hls::print("Entered while loop u_pointer: %d\n", u_pointer);
          if(entry_v > entry_u){
            u_pointer+=1;
            if(v_pointer < v_size && u_pointer < u_size)
            {
              entry_u = elements_u.read();
              u_popped+=1;
            }
              
          } else if (entry_v < entry_u){
            v_pointer+=1;
            if(v_pointer < v_size && u_pointer < u_size)
            {
              entry_v = elements_v.read();
              v_popped+=1;              
            }
              
          } else {
            count += 1;
            v_pointer+=1;
            u_pointer+=1;
            if(v_pointer < v_size && u_pointer < u_size)
            {
              v_popped+=1;
              u_popped+=1;
              entry_v = elements_v.read();
              entry_u = elements_u.read();
            }
          }
      }

         
      while(v_popped < v_size){
        #pragma HLS PIPELINE II=1
        v_popped+=1;
        elements_v.read();
      }

      while(u_popped < u_size){
        #pragma HLS PIPELINE II=1
        u_popped+=1;
        elements_u.read();
      }
    }
  }
  //hls::print("Done with task process\n");
  
}


void count_intersection_df(void * mem0, void *mem1, void *mem2, void*mem3, void *mem4, Addr v_neighbors, uint32_t v_size, uint32_t &count, Addr adj_list){
  #pragma HLS DATAFLOW
  hls::stream<uint32_t> elements_v_fifo, elements_u_fifo;

  hls::stream<Addr> u_neighbors_fifo;
  hls::stream<uint32_t> u_size_fifo_readMemU;
  hls::stream<uint32_t> u_size_fifo_readMemV;
  hls::stream<uint32_t> u_size_fifo_processData;


  #pragma HLS STREAM type=fifo variable=elements_v_fifo depth=64  
  #pragma HLS STREAM type=fifo variable=elements_u_fifo depth=64
  #pragma HLS STREAM type=fifo variable=u_neighbors_fifo depth=4
  #pragma HLS STREAM type=fifo variable=u_size_fifo_readMemU depth=4
  #pragma HLS STREAM type=fifo variable=u_size_fifo_readMemV depth=4
  #pragma HLS STREAM type=fifo variable=u_size_fifo_processData depth=4

  // #pragma HLS bind_storage type=fifo variable=elements_v_fifo impl=URAM
  // #pragma HLS bind_storage type=fifo variable=elements_u_fifo impl=URAM
    
  readVertex(mem0, mem1, mem2, v_neighbors, v_size, adj_list, u_neighbors_fifo, u_size_fifo_readMemU, u_size_fifo_readMemV, u_size_fifo_processData);

  //readMem(elements_v_fifo, elements_u_fifo, mem3, mem4, v_neighbors, u_neighbors_fifo, v_size, u_size_fifo_readMem);

  readMemU(elements_u_fifo, mem3, v_size, u_neighbors_fifo, u_size_fifo_readMemU);
  readMemV(elements_v_fifo, mem4, v_neighbors, v_size, u_size_fifo_readMemV);

  processData(elements_v_fifo, elements_u_fifo, v_size, u_size_fifo_processData, count);
}



void vertex_map(void *mem,
                void *mem_0,
                void *mem_1,
                void *mem_2,
                void *mem_3,
                void *mem_4,
                hls::stream<vertex_map_args> &taskIn,
                hls::stream<uint64_t> &argOut) {
#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = argOut
#pragma HLS INTERFACE mode = m_axi port = mem bundle=gmem channel=0 latency=64 max_widen_bitwidth=256 
#pragma HLS INTERFACE mode = m_axi port = mem_0 bundle=gmem channel=1 latency=64 num_write_outstanding=1 num_read_outstanding=64 max_read_burst_length=16 max_widen_bitwidth=256 
#pragma HLS INTERFACE mode = m_axi port = mem_1 bundle=gmem channel=2 latency=64 num_write_outstanding=1 num_read_outstanding=64 max_read_burst_length=16 max_widen_bitwidth=256
#pragma HLS INTERFACE mode = m_axi port = mem_2 bundle=gmem channel=3 latency=64 num_write_outstanding=1 num_read_outstanding=64 max_read_burst_length=16 max_widen_bitwidth=256
#pragma HLS INTERFACE mode = m_axi port = mem_3 bundle=gmem channel=4 latency=64 num_write_outstanding=1 num_read_outstanding=64 max_read_burst_length=16 max_widen_bitwidth=256
#pragma HLS INTERFACE mode = m_axi port = mem_4 bundle=gmem channel=5 latency=64 num_write_outstanding=1 num_read_outstanding=64 max_read_burst_length=16 max_widen_bitwidth=256


#pragma HLS cache port=mem_0 lines=2 depth=32 
#pragma HLS cache port=mem_1 lines=2 depth=32
#pragma HLS cache port=mem_2 lines=2 depth=32
#pragma HLS cache port=mem_3 lines=2 depth=32
#pragma HLS cache port=mem_4 lines=2 depth=32

#pragma HLS PIPELINE  

  auto task = taskIn.read();
  Addr adj_list = task.adj_list;
  uint32_t v = task.vertex;
  Addr triangle_count_entry = task.triangle_count_entry;

  uint32_t count = 0;

  // Iterate over the neighbours of the vertex
  Addr v_neighbors = MEM_ARR_IN(mem, adj_list, v, Addr);
  uint32_t v_size_neighbors = MEM_ARR_IN(mem, v_neighbors, 0, uint32_t);

  if(v_size_neighbors)
    count_intersection_df(mem_0, mem_1, mem_2, mem_3, mem_4, v_neighbors, v_size_neighbors, count, adj_list);


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
