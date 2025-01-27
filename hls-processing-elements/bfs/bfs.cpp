#include "../descriptors.h"
#include <cstddef>
#include <cstdint>
#include <etc/autopilot_ssdm_op.h>
#include <string.h>
#include <stdio.h>
#include <cmath>
#include <sys/types.h>
#include <algorithm>
#include <cstring>


#define LOCAL_SET_SIZE (1 << 17) 
#define REMOTE_SETS_BUFFER_SIZE 256

#define NEIGHBOURS_BUFFER_SIZE 32


bool cond(void *mem, Addr flag_visited, int vertex) {
#pragma HLS INTERFACE mode = m_axi port = mem
  return MEM_ARR_IN(mem, flag_visited, vertex, uint8_t) == 0;
}

bool test_and_set(void *mem_0, void * mem_1, Addr flag_visited, int vertex) {
  // std::mutex mtx;
  // std::lock_guard<std::mutex> lock(mtx);
  if (MEM_ARR_IN(mem_0, flag_visited, vertex, uint8_t) == 0) {
    // flag_visited[vertex] = 1;
    MEM_ARR_OUT(mem_1, flag_visited, vertex, uint8_t, 1);
    return true;
  }
  return false;
}

bool update(void * mem_0, void * mem_1, void * mem_2, Addr flag_visited, uint32_t neighbor, uint32_t d,
            uint16_t round) {

  if (test_and_set(mem_0, mem_1,  flag_visited, neighbor)) {
    
    // d[neighbor] = round;
    MEM_ARR_OUT(mem_2, d, neighbor, uint16_t, round);
    return true;
  }
  return false;
}

void edgeMapParallel(void * mem_0, void * mem_1, void * mem_2, void * mem_3, hls::stream<task_args> &taskIn,
                     hls::stream<uint64_t> &argOut) {
#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = argOut
#pragma HLS INTERFACE mode = m_axi port = mem_0 bundle=gmem
#pragma HLS INTERFACE mode = m_axi port = mem_1 bundle=gmem
#pragma HLS INTERFACE mode = m_axi port = mem_2 bundle=gmem
#pragma HLS INTERFACE mode = m_axi port = mem_3 bundle=gmem
#pragma HLS PIPELINE off

  volatile uint32_t loop_done = 0;
  
  auto task = taskIn.read();
  uint32_t vertex = task.vertex;
  uint16_t round = task.round;
  Addr g_edges = task.g_edges;
  Addr flag_visited = task.flag_visited;
  Addr d = task.d;
  Addr set = task.set;
  uint64_t cont = task.cont;

  // TODO: THIS IS A DAE, HOW TO DO IT IN HLS?
  Addr neighbors = MEM_ARR_IN(mem_0, g_edges, vertex, Addr);
  uint32_t size_neighbors = MEM_ARR_IN(mem_1, neighbors, 0, uint32_t);
  uint32_t set_size = 0;

  // Read the neighbour verticies in chuncks
  uint32_t neighbours_buffer[NEIGHBOURS_BUFFER_SIZE];
  bool update_buffer[NEIGHBOURS_BUFFER_SIZE];

  for (uint32_t i = 0; i < size_neighbors; i+=NEIGHBOURS_BUFFER_SIZE) {
    #pragma HLS PIPELINE II=1
    
    for(int j = 0; j < NEIGHBOURS_BUFFER_SIZE; j++) {
      #pragma HLS PIPELINE II=1
      neighbours_buffer[j] = MEM_ARR_IN(mem_1, neighbors, i + 1 + j, uint32_t);
    }

    for(int j = 0; (j < NEIGHBOURS_BUFFER_SIZE) && (i + j < size_neighbors); j++){
      #pragma HLS PIPELINE II=1
      update_buffer[j] = update(mem_0, mem_1, mem_2, flag_visited, neighbours_buffer[j], d, round);
    }

    for(int j = 0; (j < NEIGHBOURS_BUFFER_SIZE) && (i + j < size_neighbors); j++){
      #pragma HLS PIPELINE II=1 
      if(update_buffer[j]){
        set_size++;
        MEM_ARR_OUT(mem_3, set, set_size, uint32_t, neighbours_buffer[j]);
      }
    }

    loop_done += NEIGHBOURS_BUFFER_SIZE;
  }
  
  MEM_ARR_OUT(mem_3, set, 0, uint32_t, set_size);
  loop_done += 1;
  
  while (loop_done < set_size) {
  }
  argOut.write(cont);

}

void edgeMap(void *mem,
             void *mem_2,
             hls::stream<uint64_t> &mallocIn,
             hls::stream<edgemap_args> &taskIn,
             hls::stream<task_args> &taskOutGlobal,
             hls::stream<edgeMap_spawn_next> &spawnNext,
             hls::stream<uint64_t> &closureIn, hls::stream<uint64_t> &argOut) {
#pragma HLS INTERFACE mode = axis port = mallocIn
#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = taskOutGlobal
#pragma HLS INTERFACE mode = axis port = spawnNext
#pragma HLS INTERFACE mode = axis port = closureIn
#pragma HLS INTERFACE mode = axis port = argOut
#pragma HLS INTERFACE mode = m_axi port = mem bundle=gmem
#pragma HLS INTERFACE mode = m_axi port = mem_2 bundle=gmem

#pragma HLS PIPELINE off

  auto task = taskIn.read();
  Addr g_edges = task.g_edges;
  Addr f = task.f;
  Addr flag_visited = task.flag_visited;
  Addr d = task.d;
  Addr sets = task.sets;
  uint32_t g_size = task.g_size;
  uint16_t round = task.round;
  uint32_t size = task.size;
  uint8_t is_sync = task.is_sync;

  volatile uint32_t memoryDependencyVar1 = 0;


  // To use these at the first iteration we have to change the driver to set the first task as is_sync
  // Under this section we collect all the sets from the parallel runs into a single set.
  // We create a local buffer to hold the bigger set to be syntheized as BRAM (so we can group sets locally)
  uint32_t f_size = 0;   
  uint32_t local_grouping_set [LOCAL_SET_SIZE];
  
  bool element_alive_flag[LOCAL_SET_SIZE];
  for(int i = 0; i < LOCAL_SET_SIZE; i++){
      #pragma HLS UNROLL factor=32
      //#pragma HLS PIPELINE
      element_alive_flag[i] = false;
  }

//   bool element_alive_flag [LOCAL_SET_SIZE];
//   memset(element_alive_flag, 0, sizeof(element_alive_flag));

  if (is_sync) {
      
    

    for (int i = 0; i < size; i++) {
      // Get the address of the set and its size
      Addr set_i = MEM_ARR_IN(mem_2, sets, i, Addr);  // 2286D000
      uint32_t set_i_size = MEM_ARR_IN(mem_2, set_i, 0, uint32_t); // 228B9000

    
      for (int j = 0; j < set_i_size; j+=REMOTE_SETS_BUFFER_SIZE) {
       
        // Insert the elements from the remote set into the local set
        // start by reading a part of the set into the remote buffer
        //memcpy((char *) remote_sets_buffer, ((char(*))((uint8_t *)(mem) + (set_i) + (j) * sizeof(uint32_t))), REMOTE_SETS_BUFFER_SIZE << 2);
        int32_t hash_position[REMOTE_SETS_BUFFER_SIZE];
        uint32_t remote_sets_buffer[REMOTE_SETS_BUFFER_SIZE];
        
        for(int k = 0; k < REMOTE_SETS_BUFFER_SIZE; k++){
          //#pragma HLS UNROLL factor = 8
          #pragma HLS PIPELINE
          remote_sets_buffer[k] = MEM_ARR_IN(mem, set_i, j + k + 1, uint32_t);
          hash_position[k] = 0;
        }

        for(int k = 0; (k < REMOTE_SETS_BUFFER_SIZE) && ((k + j) < set_i_size); k++) {
          uint32_t elm = remote_sets_buffer[k];
          uint32_t hash = ((elm + hash_position[k]) % LOCAL_SET_SIZE);
        
          if(element_alive_flag[hash] == false){
            element_alive_flag[hash] = true;
            local_grouping_set[hash] = elm;
            f_size+=1;
          } else if(local_grouping_set[hash] != elm) {
            hash_position[k] +=1;
            k-=1;
          }
        }
      }
      memoryDependencyVar1+=1;
    }

    while (memoryDependencyVar1 < size) {
    }
    if (f_size == 0) {
      argOut.write(task.cont);
    } else {
      round++;
      is_sync = 0;
    }
  }

  if (!is_sync) {
    // size_t f_size = f.getSize();
    //uint32_t f_size = MEM_ARR_IN(mem, f, 0, uint32_t);
    // Set **new_sets = new Set *[f.getSize()];
    // Addr new_sets = mallocIn.read();
    
    // Use the same sets allocated before (should be allocated in the driver)

    // spawn_next edgeMap(mem, mallocIn, g_edges, g_size, f, flag_visited,
    // round, d, new_sets, f_size, true);

    edgemap_args edgemap_args0;
    edgemap_args0.counter = f_size;
    edgemap_args0.g_size = g_size;
    edgemap_args0.g_edges = g_edges;
    edgemap_args0.f = f;
    edgemap_args0.flag_visited = flag_visited;
    edgemap_args0.round = round;
    edgemap_args0.d = d;
    edgemap_args0.sets = sets;
    edgemap_args0.size = f_size;
    edgemap_args0.is_sync = 1;
    edgeMap_spawn_next sn0;
    sn0.addr = closureIn.read();
    sn0.data = edgemap_args0;
    sn0.allow = f_size;
    sn0.size = 6;

    // uint8_t memoryDependencyVar = 0;

    spawnNext.write(sn0);

    // uint32_t pre_allocated_addresses_count = 0;
    // bool flag_init = false;

    int next_valid_index = 0;

    for (uint32_t i = 0; i < f_size; i++) {
      // new_sets[i] = new Set();

      // Let's realloc the sets from the last round
      Addr new_sets_i;

    //   if(pre_allocated_addresses_count < size){
    //       new_sets_i = MEM_ARR_IN(mem_2, sets, pre_allocated_addresses_count, Addr);
    //       MEM_ARR_OUT(mem, new_sets_i, 0, uint32_t, 0);
    //       pre_allocated_addresses_count++;
    //   } else if ((MEM_ARR_IN(mem, sets, pre_allocated_addresses_count, Addr) != 0) && !flag_init) {
    //       new_sets_i =  MEM_ARR_IN(mem_2, sets, pre_allocated_addresses_count, Addr);
    //       MEM_ARR_OUT(mem, new_sets_i, 0, uint32_t, 0);
    //       pre_allocated_addresses_count++;  
    //   } else {
          new_sets_i = mallocIn.read(); 
          MEM_ARR_OUT(mem, sets, i, Addr, new_sets_i);
    //       flag_init = true;
    //   }
      
      /*cilk_spawn edgeMapParallel(g, f[i], flag_visited, round, d,
       * new_sets[i]); */
      uint32_t f_i;
      for(int j = next_valid_index; j < LOCAL_SET_SIZE; j++){
        if(element_alive_flag[j] == true)
        {
          f_i = local_grouping_set[j];
          next_valid_index = j + 1;
          break;
        }
      }

      // spawn edgeMapParallel(mem, g_edges, g_size, f_i, flag_visited, round,
      // d, new_sets_i);
      task_args task_args0;
      task_args0.g_edges = g_edges;
      task_args0.vertex = f_i;
      task_args0.flag_visited = flag_visited;
      task_args0.round = round;
      task_args0.d = d;
      task_args0.set = new_sets_i;
      task_args0.cont = sn0.addr;
      taskOutGlobal.write(task_args0);

    }
    // sn0.allow = memoryDependencyVar;

    /*cilk_sync;*/
  }
}

