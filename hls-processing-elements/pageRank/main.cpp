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
#include "hls_stream.h"
#include "hls_task.h"

#define epsilon (1e-6)
#define damping 0.85



void readDiffs(void * mem,
                hls::stream<Addr> & diffs_stream,
                hls::stream<uint32_t> & vertex_count_stream,                
                hls::stream<double> & adder_in_feed
            ){

  auto vertex_count = vertex_count_stream.read();
  auto diffs = diffs_stream.read();
  for(int i = 0; i < vertex_count; i++){
    #pragma HLS PIPELINE II =1
    double diff_i = MEM_ARR_IN(mem, diffs, i, double);
    adder_in_feed.write(diff_i);    
  }
}

void diffs_adder_function(hls::stream<uint32_t>& v_size_stream, hls::stream<double>& adder_results, hls::stream<double>& adder_in_feed_0, hls::stream<double>& adder_in_feed_1, hls::stream<bool> &executeFlag){
  
  double value0, value1;
  auto v_size = v_size_stream.read();
  double sum = 0;

  bool flagged_next = false;

  for(int i = 0; i < v_size; i++){
    #pragma HLS PIPELINE II=1
    value0 = adder_in_feed_0.read();
    value1 = adder_in_feed_1.read();
    adder_results.write( value0 + value1);
    if((value0 > epsilon || value1 > epsilon) && !flagged_next){
      flagged_next = true;
      executeFlag.write(true);
    }
  }

  for(int i = 0; i < adder_latency; i++){
    #pragma HLS PIPELINE
    sum += adder_in_feed_0.read();
  }

  if(sum > epsilon && !flagged_next){
    executeFlag.write(true);
  }

  if(sum < epsilon){
    executeFlag.write(false);
  }
  
}

void diffs_feed_adder_value(hls::stream<uint32_t> & v_size_stream, hls::stream<double>& adder_results, hls::stream<double>& adder_in_feed){

  auto v_size = v_size_stream.read();

  for(int i = 0; i < adder_latency; i++) {
    #pragma HLS PIPELINE II=1
    adder_in_feed.write(0);
  }

  for(int i = 0; i < v_size; i++){ 
    #pragma HLS PIPELINE II=1
    adder_in_feed.write(adder_results.read());
  }  
}



void createNextTasks(hls::stream<vertex_map_args> &taskOutGlobal, 
                     hls::stream<pageRank_spawn_next> &spawnNext,
                     hls::stream<uint64_t> &closureIn,
                     hls::stream<bool> &executeFlag,
                     hls::stream<pageRankReduce_args> & taskStream,
                     hls::stream<uint64_t> &argOut,
                     hls::stream<uint16_t> & iter){

    uint16_t iterationCount = iter.read();
    bool execute = executeFlag.read();
    auto task = taskStream.read();
    task.iter = task.iter-1;
    if(execute && iterationCount > 0){
      std::swap(task.Pcurr, task.Pnext);
      task.counter = task.vertex_count;
      pageRank_spawn_next sn0;
      sn0.addr = closureIn.read();
      sn0.data = task;
      sn0.allow = task.vertex_count;
      sn0.size = 6; //TODO

      spawnNext.write(sn0);

      for(uint32_t i = 0; i < task.vertex_count; i++){
        #pragma HLS PIPELINE
        vertex_map_args vma0;
        vma0.Pcurr = task.Pcurr;
        vma0.Pnext = task.Pnext;
        vma0.return_address = sn0.addr;
        vma0.vertex = i;
        vma0.vertex_count = task.vertex_count;
        vma0.adj_list = task.adj_list;
        vma0.diffs = task.diffs;
        taskOutGlobal.write(vma0);
      }
    } else {
      argOut.write(task.cont);
    }
}

void readReduceTask(hls::stream<pageRankReduce_args> &taskIn, 
                    hls::stream<pageRankReduce_args> & taskToCreate, 
                    hls::stream<Addr> & diffs_stream,
                    hls::stream<uint32_t> & v_size_0,
                    hls::stream<uint32_t> & v_size_1,
                    hls::stream<uint32_t> & v_size_2,
                    hls::stream<uint16_t> & iter){
  auto task = taskIn.read();
  taskToCreate.write(task);
  diffs_stream.write(task.diffs);
  v_size_0.write(task.vertex_count);
  v_size_1.write(task.vertex_count);
  v_size_2.write(task.vertex_count);
  iter.write(task.iter);
  
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
#pragma HLS DATAFLOW
#pragma HLS INTERFACE mode=ap_ctrl_hs port=return


  #pragma HLS cache port=mem lines=2 depth=32

  hls_thread_local hls::stream<double, 128> adder_in_feed_0;
  hls_thread_local hls::stream<double, 128> adder_in_feed_1;
  hls_thread_local hls::stream<double, 128> adder_results;
  hls_thread_local hls::stream<uint32_t, 9> v_size_0;
  hls_thread_local hls::stream<uint32_t, 9> v_size_1;
  hls_thread_local hls::stream<uint32_t, 9> v_size_2;
  hls_thread_local hls::stream<pageRankReduce_args, 9> taskToCreate;
  hls_thread_local hls::stream<Addr, 9> diffs_stream;
  hls_thread_local hls::stream<bool, 9> executeFlag;
  hls_thread_local hls::stream<uint16_t, 9> iter;

  
  
  hls_thread_local hls::task t0(readReduceTask, taskIn, taskToCreate, diffs_stream, v_size_0, v_size_1, v_size_2, iter);
  hls_thread_local hls::task t1(diffs_feed_adder_value, v_size_0, adder_results, adder_in_feed_0);
  hls_thread_local hls::task t2(readDiffs, mem, diffs_stream, v_size_1, adder_in_feed_1);
  hls_thread_local hls::task t3(diffs_adder_function, v_size_2, adder_results, adder_in_feed_0, adder_in_feed_1, executeFlag);
  hls_thread_local hls::task t4(createNextTasks, taskOutGlobal, spawnNext, closureIn, executeFlag, taskToCreate, argOut, iter);

  
} 

void readVertex(void * mem0,
                void * mem1, 
                hls::stream<Addr> & v_neighbours_stream, 
                hls::stream<uint32_t> &v_size_stream, 
                hls::stream<Addr> &adj_list_stream,
                hls::stream<Addr> &Pcurr_stream,
                hls::stream<double>& adder_in_feed
            ){


  auto v_neighbours = v_neighbours_stream.read();
  auto v_size = v_size_stream.read();
  auto adj_list = adj_list_stream.read();
  auto Pcurr = Pcurr_stream.read();

  for(int i = 0; i < v_size; i++){
    #pragma HLS PIPELINE II =1
    uint32_t u = MEM_ARR_IN(mem0, v_neighbours, i + 1, uint32_t);
    Addr u_neighbors = MEM_ARR_IN(mem1, adj_list, u, Addr);
    uint32_t u_degree =  MEM_ARR_IN(mem0, u_neighbors, 0, uint32_t);
    double Pcurr_u = MEM_ARR_IN(mem1, Pcurr, u, double);
    double contribution = Pcurr_u / u_degree; // NOTE THIS MIGHT CAUSE SHIT!
    adder_in_feed.write(contribution);    
  }
}

void adder_function(hls::stream<uint32_t>& v_size_stream, 
                    hls::stream <double> & sum_stream, 
                    hls::stream<double>& adder_results, 
                    hls::stream<double>& adder_in_feed_0, 
                    hls::stream<double>& adder_in_feed_1
                    ){
  
  double value0, value1;
  auto v_size = v_size_stream.read();
  double sum = 0;
  for(int i = 0; i < v_size; i++){
    #pragma HLS PIPELINE II=1
    adder_results.write(adder_in_feed_0.read() + adder_in_feed_1.read());
  }

  for(int i = 0; i < adder_latency; i++){
    #pragma HLS PIPELINE
    sum += adder_in_feed_0.read();
  }

  sum_stream.write(sum);
 
}

void feed_adder_value(hls::stream<uint32_t> & v_size_stream, hls::stream<double>& adder_results, hls::stream<double>& adder_in_feed){

  auto v_size = v_size_stream.read();

  for(int i = 0; i < adder_latency; i++) {
    #pragma HLS PIPELINE II=1
    adder_in_feed.write(0);
  }

  for(int i = 0; i < v_size; i++){ // Not the best approach
    #pragma HLS PIPELINE II=1
    adder_in_feed.write(adder_results.read());
  }  
}

//template<typename T>
void fork3(hls::stream<uint32_t>& in, hls::stream<uint32_t>& out0, hls::stream<uint32_t>& out1, hls::stream<uint32_t>& out2){
  auto val = in.read();
  out0.write(val);
  out1.write(val);
  out2.write(val);
}

// uint8_t initial_tag_arbiter = 0;
// uint8_t initial_tag_dearbiter = 0;
const int max_cases = 2;


// Apply RoundRobin for now (blocking at busy)
void arbiter(
              // input
              hls::stream<Addr>& v_neighbours_stream, 
              hls::stream<Addr>& Pcurrs,
              hls::stream<Addr>& adj_lists,
              hls::stream<uint32_t>& v_size, 
              hls::stream<vertex_map_args> &taskToWriteArg,

              // output
              hls::stream<Addr>& v_neighbours_stream_0, 
              hls::stream<Addr>& Pcurrs_0,
              hls::stream<Addr>& adj_lists_0,
              hls::stream<uint32_t>& v_size_0, 
              hls::stream<vertex_map_args> &taskToWriteArg_0,

              hls::stream<Addr>& v_neighbours_stream_1, 
              hls::stream<Addr>& Pcurrs_1,
              hls::stream<Addr>& adj_lists_1,
              hls::stream<uint32_t>& v_size_1, 
              hls::stream<vertex_map_args> &taskToWriteArg_1
){

  for(int initial_tag_arbiter = 0; initial_tag_arbiter < 2; initial_tag_arbiter++)
    switch(initial_tag_arbiter){
      case 0:
        v_neighbours_stream_0.write(v_neighbours_stream.read());
        Pcurrs_0.write(Pcurrs.read());
        adj_lists_0.write(adj_lists.read());
        v_size_0.write(v_size.read());
        taskToWriteArg_0.write(taskToWriteArg.read());
        break;
      case 1:
        v_neighbours_stream_1.write(v_neighbours_stream.read());
        Pcurrs_1.write(Pcurrs.read());
        adj_lists_1.write(adj_lists.read());
        v_size_1.write(v_size.read());
        taskToWriteArg_1.write(taskToWriteArg.read());
        break;
    };
  //initial_tag_arbiter = (initial_tag_arbiter + 1) % max_cases;
}

void deArbiter(
            // input from task read
            hls::stream<vertex_map_args> &taskIn_0,
            hls::stream<vertex_map_args> &taskIn_1,
            
            // input from processing
            hls::stream<double>& sums_0,
            hls::stream<double>& sums_1,

            // output to write arg
            hls::stream<vertex_map_args> &taskIn_o,
            hls::stream<double>& sums_o

){
  for(int initial_tag_dearbiter = 0; initial_tag_dearbiter < 2; initial_tag_dearbiter++)
    switch(initial_tag_dearbiter){
      case 0:
        taskIn_o.write(taskIn_0.read());
        sums_o.write(sums_0.read());
        break;
      case 1:
        taskIn_o.write(taskIn_1.read());
        sums_o.write(sums_1.read());
        break;
    };
  //initial_tag_dearbiter = (initial_tag_dearbiter + 1) % max_cases; 
}



void read_task( void * mem, 
                hls::stream<vertex_map_args> &taskIn, 
                hls::stream<Addr>& v_neighbours_stream, 
                hls::stream<Addr>& Pcurrs,
                hls::stream<Addr>& adj_lists,
                hls::stream<uint32_t>& v_size, 
                hls::stream<vertex_map_args> &taskToWriteArg 
              ){
    #pragma HLS PIPELINE
    


    if(taskIn.empty()) return;
    
     
    auto task = taskIn.read();

    Addr v_neighbours = MEM_ARR_IN(mem, task.adj_list, task.vertex, Addr);
    uint32_t v_neighbours_size = MEM_ARR_IN(mem, v_neighbours, 0, uint32_t);

    v_neighbours_stream.write(v_neighbours);
    
    v_size.write(v_neighbours_size);
    
    Pcurrs.write(task.Pcurr);
    adj_lists.write(task.adj_list);
    taskToWriteArg.write(task);  
}

void write_arg(void * mem,
              hls::stream<uint64_t> &argOut,              
              hls::stream<vertex_map_args> &taskIn,
              hls::stream<double>& sums
){
  #pragma HLS PIPELINE

  auto sum = sums.read();

  auto task = taskIn.read();
  
  auto vertex_count = task.vertex_count;
  auto Pcurr = task.Pcurr;
  auto vertex = task.vertex;
  auto diffs = task.diffs;
  auto Pnext = task.Pnext;
  auto return_address = task.return_address;

  double Pnext_v = (1 - damping) / vertex_count + damping * sum;

  double Pcurrent_v = MEM_ARR_IN(mem, Pcurr, vertex, double);
  
  double diffs_v = std::abs(Pnext_v - Pcurrent_v);

  MEM_ARR_OUT(mem, Pnext, vertex, double, Pnext_v);
  MEM_ARR_OUT(mem, diffs, vertex, double, diffs_v);
  
  volatile double val = MEM_ARR_IN(mem, diffs, vertex, double);

  val = MEM_ARR_IN(mem, diffs, vertex, double);
  
  if(val == diffs_v){
      argOut.write(return_address);
  }

}


void process(void * mem_0,
                void * mem_1,
                void * mem_2,
                void * mem_3,
                void * mem_4,
                void * mem_5,
                hls::stream<vertex_map_args> &taskIn,
                hls::stream<uint64_t> &argOut){
                  #pragma HLS DATAFLOW
                  hls_thread_local hls::stream<double, 128> adder_in_feed_0[2];
                hls_thread_local hls::stream<double, 128> adder_in_feed_1[2];
                hls_thread_local hls::stream<double, 128> adder_results[2];
                hls_thread_local hls::stream<Addr, 9> v_neighbours_stream[2];

                hls_thread_local hls::stream<uint32_t, 9> v_size_toArbiter;
                hls_thread_local hls::stream<uint32_t, 9> v_size_toFork[2];
                hls_thread_local hls::stream<uint32_t, 9> v_size [6];

                hls_thread_local hls::stream<Addr, 9> Pcurrs_toArbiter;
                hls_thread_local hls::stream<Addr, 9> adj_lists_toArbiter;
                hls_thread_local hls::stream<Addr, 9> v_neighbours_stream_toArbiter;

                hls_thread_local hls::stream<Addr, 9> Pcurrs[2];
                hls_thread_local hls::stream<Addr, 9> adj_lists[2];
                hls_thread_local hls::stream<double, 9> sums_stream[2];

                hls_thread_local hls::stream<vertex_map_args, 9> taskToArbiter;
                hls_thread_local hls::stream<vertex_map_args, 9> taskFromArbiter[2];
                hls_thread_local hls::stream<vertex_map_args, 9> taskFromDeArbiter;
                hls_thread_local hls::stream<double, 9> sums_stream_fromDeArbiter;
                
   
                // Instantiate read task
                hls_thread_local hls::task t0(read_task, 
                                              mem_0, 
                                              taskIn, 
                                              v_neighbours_stream_toArbiter, 
                                              Pcurrs_toArbiter, 
                                              adj_lists_toArbiter,
                                              v_size_toArbiter, 
                                              taskToArbiter 
                                              );

                // Instantiate the arbiter
                hls_thread_local hls::task t1 ( arbiter, 
                                                v_neighbours_stream_toArbiter, 
                                                Pcurrs_toArbiter, 
                                                adj_lists_toArbiter, 
                                                v_size_toArbiter, 
                                                taskToArbiter, 
                                                v_neighbours_stream[0],
                                                Pcurrs[0],
                                                adj_lists[0],
                                                v_size_toFork[0],
                                                taskFromArbiter[0],
                                                v_neighbours_stream[1],
                                                Pcurrs[1],
                                                adj_lists[1],
                                                v_size_toFork[1],
                                                taskFromArbiter[1]
                                                );
                                                
                // Create two 3Fork tasks for the v_size stream
                hls_thread_local hls::task t100(fork3, v_size_toFork[0], v_size[0], v_size[1], v_size[2]);
                hls_thread_local hls::task t101(fork3, v_size_toFork[1], v_size[3], v_size[4], v_size[5]);

                hls_thread_local hls::task t200 (feed_adder_value, v_size[0], adder_results[0], adder_in_feed_0[0]);
                hls_thread_local hls::task t201 (readVertex, mem_1, mem_2, v_neighbours_stream[0], v_size[1], adj_lists[0], Pcurrs[0], adder_in_feed_1[0]);
                hls_thread_local hls::task t202 (adder_function, v_size[2], sums_stream[0], adder_results[0], adder_in_feed_0[0], adder_in_feed_1[0]);

                hls_thread_local hls::task t300 (feed_adder_value, v_size[3], adder_results[1], adder_in_feed_0[1]);
                hls_thread_local hls::task t301 (readVertex, mem_3, mem_4, v_neighbours_stream[1], v_size[4], adj_lists[1], Pcurrs[1], adder_in_feed_1[1]);
                hls_thread_local hls::task t302 (adder_function, v_size[5], sums_stream[1], adder_results[1], adder_in_feed_0[1], adder_in_feed_1[1]);

                // Instantiate the deArbiter
                hls_thread_local hls::task t2 ( deArbiter, 
                                                taskFromArbiter[0],
                                                taskFromArbiter[1],
                                                sums_stream[0],
                                                sums_stream[1], 
                                                taskFromDeArbiter, 
                                                sums_stream_fromDeArbiter
                                                ); 
                                               
                // Instantiate the write arg
                hls_thread_local hls::task t3 (write_arg, 
                                               mem_5, 
                                               argOut, 
                                               taskFromDeArbiter, 
                                               sums_stream_fromDeArbiter);
}



void vertex_map(void * mem_0,
                void * mem_1,
                void * mem_2,
                void * mem_3,
                void * mem_4,
                void * mem_5,
                hls::stream<vertex_map_args> &taskIn,
                hls::stream<uint64_t> &argOut){

        #pragma HLS INTERFACE port=return mode=ap_ctrl_chain
#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = argOut
#pragma HLS INTERFACE mode = m_axi port = mem_0 bundle=gmem channel=0 latency=64 
#pragma HLS INTERFACE mode = m_axi port = mem_1 bundle=gmem channel=1 latency=64 num_write_outstanding=1 num_read_outstanding=64 max_read_burst_length=16 max_widen_bitwidth=256
#pragma HLS INTERFACE mode = m_axi port = mem_2 bundle=gmem channel=2 latency=64 num_write_outstanding=1 num_read_outstanding=64 max_read_burst_length=16 max_widen_bitwidth=256
#pragma HLS INTERFACE mode = m_axi port = mem_3 bundle=gmem channel=3 latency=64 num_write_outstanding=1 num_read_outstanding=64 max_read_burst_length=16 max_widen_bitwidth=256
#pragma HLS INTERFACE mode = m_axi port = mem_4 bundle=gmem channel=4 latency=64 num_write_outstanding=1 num_read_outstanding=64 max_read_burst_length=16 max_widen_bitwidth=256
#pragma HLS INTERFACE mode = m_axi port = mem_5 bundle=gmem channel=5 latency=64 num_write_outstanding=1 num_read_outstanding=64 max_read_burst_length=16 max_widen_bitwidth=256

#pragma HLS cache port=mem_1 lines=2 depth=32
#pragma HLS cache port=mem_2 lines=2 depth=32
#pragma HLS cache port=mem_3 lines=2 depth=32
#pragma HLS cache port=mem_4 lines=2 depth=32

process(mem_0,
                 mem_1,
                 mem_2,
                 mem_3,
                 mem_4,
                 mem_5,
                taskIn,
                argOut);
                

}
