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
#include "hls_task.h"


void readVertex(void * mem0,
                void * mem1, 
                void * mem2, 
                hls::stream<Addr> & v_neighbors_s, 
                hls::stream<uint32_t> & v_size_instream,
                hls::stream<Addr> & adj_list_s,
                hls::stream<Addr>& u_neighbors_fifo, 
                hls::stream<uint32_t>& u_size_fifo0, 
                hls::stream<uint32_t>& u_size_fifo1,
                hls::stream<uint32_t>& u_size_fifo2){
  
    uint32_t v_size = v_size_instream.read();
    Addr v_neighbors = v_neighbors_s.read();
    Addr adj_list = adj_list_s.read();

    for(int i = 0; i < v_size; i++){
      #pragma HLS PIPELINE II =1 
      
      uint32_t u = MEM_ARR_IN(mem0, v_neighbors, i + 1, uint32_t);
      
      Addr u_neighbors = MEM_ARR_IN(mem1, adj_list, u, Addr);
      
      
      uint32_t u_size =  MEM_ARR_IN(mem2, u_neighbors, 0, uint32_t);
      u_size_fifo0.write(u_size);
      u_size_fifo1.write(u_size);
      u_size_fifo2.write(u_size);
      u_neighbors_fifo.write(u_neighbors);
    }
}

void readMemV(hls::stream<uint32_t> &elements_v,
              void * mem_v,
              hls::stream<Addr> & v_neighbors_s,
              hls::stream<uint32_t>& v_size_s,
              hls::stream<uint32_t> & u_size_fifo
      ){
          uint32_t v_size = v_size_s.read();
          Addr v_neighbors = v_neighbors_s.read();
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
              hls::stream<uint32_t> & v_size_s,
              hls::stream<Addr> & u_neighbors_fifo,
              hls::stream<uint32_t> & u_size_fifo
      ){  
      uint32_t v_size = v_size_s.read();
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
              hls::stream<uint32_t>  &v_size_s,
              hls::stream<uint32_t> &u_size_fifo,
              hls::stream<uint32_t> &count_s){
    uint32_t count = 0;
    uint32_t entry_v, entry_u;
    uint32_t v_size = v_size_s.read();

    for(int j = 0; j < v_size; j++){
      uint32_t u_size = u_size_fifo.read();
      uint32_t v_pointer = 0;
      uint32_t u_pointer = 0;  

      
      if(u_size > 0){
        entry_v = elements_v.read();
        entry_u = elements_u.read();

        uint32_t v_popped = 1;
        uint32_t u_popped = 1;

        while(v_pointer < v_size && u_pointer < u_size){
            #pragma HLS PIPELINE II=2

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
    count_s.write(count);

}


void readTask(void * mem, 
              hls::stream<vertex_map_args> & taskIn, 
              hls::stream<Addr> & addresses_neighbours_0,
              hls::stream<Addr> & addresses_neighbours_1, 
              hls::stream<Addr> & addresses_adj_lists, 
              hls::stream<uint32_t> & sizes_0,
              hls::stream<uint32_t> & sizes_1,
              hls::stream<uint32_t> & sizes_2,
              hls::stream<uint32_t> & sizes_3, 
              hls::stream<Addr> & triangleCountEntries,  
              hls::stream<Addr> & return_address_queue,
              hls::stream<bool> & fastFinishDelievery){
        
        //if(taskIn.empty()) return;

        auto task = taskIn.read();

        Addr v_neighbors = MEM_ARR_IN(mem, task.adj_list, task.vertex, Addr);
        uint32_t v_size_neighbors = MEM_ARR_IN(mem, v_neighbors, 0, uint32_t);
        return_address_queue.write(task.return_address);
        triangleCountEntries.write(task.triangle_count_entry);
        
        if(v_size_neighbors > 0){        
          sizes_0.write(v_size_neighbors);
          sizes_1.write(v_size_neighbors);
          sizes_2.write(v_size_neighbors);
          sizes_3.write(v_size_neighbors);
          addresses_neighbours_0.write(v_neighbors);
          addresses_neighbours_1.write(v_neighbors);
          addresses_adj_lists.write(task.adj_list);  
          fastFinishDelievery.write(false);
        } else {
          fastFinishDelievery.write(true);          
        }
}


void writeArg(void * mem, hls::stream<uint64_t> &argOut,  hls::stream<uint32_t> &count, hls::stream<Addr> & triangleCountEntries, hls::stream<Addr> & return_address_queue, hls::stream<bool> & fastFinishDelievery){


    uint32_t count_local = 0;
    bool fastFinish = fastFinishDelievery.read();
    Addr entry = triangleCountEntries.read();
    Addr return_address = return_address_queue.read();
    
    if(!fastFinish)
      count_local = count.read();
    
    MEM_ARR_OUT(mem, entry, 0, uint32_t, count_local);  
    volatile uint32_t readBackSync = count_local;
    while(MEM_ARR_IN(mem, entry, 0, uint32_t) != readBackSync){

    }
    argOut.write(return_address);

}


void vertex_map(void *mem,
                void *mem_0,
                void *mem_1,
                void *mem_2,
                void *mem_3,
                void *mem_4,
                void *mem_5,
                hls::stream<vertex_map_args> &taskIn,
                hls::stream<uint64_t> &argOut) {

// #pragma HLS INTERFACE mode=ap_ctrl_hs port=return

#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = argOut
#pragma HLS INTERFACE mode = m_axi port = mem bundle=gmem   channel=1 latency=64 max_widen_bitwidth=256 
#pragma HLS INTERFACE mode = m_axi port = mem_0 bundle=gmem channel=0 latency=64 max_widen_bitwidth=256 
#pragma HLS INTERFACE mode = m_axi port = mem_1 bundle=gmem channel=2 latency=64 num_write_outstanding=1 num_read_outstanding=64 max_read_burst_length=16 max_widen_bitwidth=256
#pragma HLS INTERFACE mode = m_axi port = mem_2 bundle=gmem channel=3 latency=64 num_write_outstanding=1 num_read_outstanding=64 max_read_burst_length=16 max_widen_bitwidth=256
#pragma HLS INTERFACE mode = m_axi port = mem_3 bundle=gmem channel=4 latency=64 num_write_outstanding=1 num_read_outstanding=64 max_read_burst_length=16 max_widen_bitwidth=256
#pragma HLS INTERFACE mode = m_axi port = mem_4 bundle=gmem channel=5 latency=64 num_write_outstanding=1 num_read_outstanding=64 max_read_burst_length=16 max_widen_bitwidth=256
#pragma HLS INTERFACE mode = m_axi port = mem_5 bundle=gmem channel=6 latency=64 num_write_outstanding=1 num_read_outstanding=64 max_read_burst_length=16 max_widen_bitwidth=256



#pragma HLS cache port=mem_1 lines=2 depth=32
#pragma HLS cache port=mem_2 lines=2 depth=32
#pragma HLS cache port=mem_3 lines=2 depth=32
#pragma HLS cache port=mem_4 lines=2 depth=32
#pragma HLS cache port=mem_5 lines=2 depth=32


#pragma HLS DATAFLOW

  hls_thread_local hls::stream<uint32_t, 128> elements_v_fifo, elements_u_fifo;
  hls_thread_local hls::stream<Addr, 32> u_neighbors_fifo;
  hls_thread_local hls::stream<uint32_t, 9> u_size_fifo_readMemU;
  hls_thread_local hls::stream<uint32_t, 32> u_size_fifo_readMemV;
  hls_thread_local hls::stream<uint32_t, 32> u_size_fifo_processData;
  hls_thread_local hls::stream<Addr, 9> addresses_neighbours_0;
  hls_thread_local hls::stream<Addr, 9> addresses_neighbours_1;
  hls_thread_local hls::stream<Addr, 9> addresses_adj_lists;
  hls_thread_local hls::stream<uint32_t, 9> sizes_0;
  hls_thread_local hls::stream<uint32_t, 9> sizes_1;
  hls_thread_local hls::stream<uint32_t, 9> sizes_2;
  hls_thread_local hls::stream<uint32_t, 9> sizes_3;
  hls_thread_local hls::stream<Addr, 10> triangleCountEntries;
  hls_thread_local hls::stream<Addr, 10> return_address_queue;
  hls_thread_local hls::stream<uint32_t, 9> count_stream;
  hls_thread_local hls::stream<bool, 10> fastFinishDelievery;

  

  hls_thread_local hls::task t0  (readTask, mem_0, taskIn, addresses_neighbours_0, addresses_neighbours_1, addresses_adj_lists, sizes_0, sizes_1, sizes_2, sizes_3, triangleCountEntries, return_address_queue, fastFinishDelievery);

  hls_thread_local hls::task t1 (readVertex, mem_1, mem_2, mem_3, addresses_neighbours_0, sizes_0, addresses_adj_lists, u_neighbors_fifo, u_size_fifo_readMemU, u_size_fifo_readMemV, u_size_fifo_processData);

  hls_thread_local hls::task t2 (readMemU, elements_u_fifo, mem_4, sizes_1, u_neighbors_fifo, u_size_fifo_readMemU);

  hls_thread_local hls::task t3 (readMemV, elements_v_fifo, mem_5, addresses_neighbours_1, sizes_2, u_size_fifo_readMemV);

  hls_thread_local hls::task t4 (processData, elements_v_fifo, elements_u_fifo, sizes_3, u_size_fifo_processData, count_stream);

  hls_thread_local hls::task t5 (writeArg, mem, argOut, count_stream, triangleCountEntries, return_address_queue, fastFinishDelievery);
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
