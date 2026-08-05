#include "util.h"
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


//memReader reads 16 elements at a time into the output argument
void memReader(void *mem, hls::stream<memReader_task> &taskIn, hls::stream<counter_continuation_update> &argOut) {
    #pragma HLS INTERFACE mode = axis port = taskIn
    #pragma HLS INTERFACE mode = axis port = argOut
    #pragma HLS INTERFACE mode = m_axi port = mem max_widen_bitwidth=512 num_read_outstanding=16
    #pragma HLS INTERFACE ap_ctrl_none port = return
    #pragma HLS PIPELINE II=1 style=flp

    memReader_task args = taskIn.read();
    counter_continuation_update update;
    update.address = args._cont;
    update.continuation_meta = args.continuation_meta;
    update.offset = args.offset;

    //The tail of a list is fetched as a full window, so this runs off the end of
    //the last adjacency list by up to ADDER_WINDOW - 1 elements. The adder never
    //compares those, but the host still has to pad the neighbour array by one
    //window so the burst stays inside the buffer.
    for (int i = 0; i < ADDER_WINDOW; i++) {
        #pragma HLS UNROLL
        update.payload[i] = MEM_ARR_IN(mem, args.start_addr, i, int);
    }
    
    argOut.write(update);
}

void adder(hls::stream<counter_continuation> &taskIn, hls::stream<adder_done_continuation_update> &argOut, hls::stream<uint64_t> &closureIn, hls::stream<memReader_taskOut> &memReaderTaskOut, hls::stream<adder_self_spawn_next> &selfSpawnNext){
    #pragma HLS INTERFACE mode = axis port = taskIn
    #pragma HLS INTERFACE mode = axis port = argOut
    #pragma HLS INTERFACE mode = axis port = closureIn
    #pragma HLS INTERFACE mode = axis port = memReaderTaskOut
    #pragma HLS INTERFACE mode = axis port = selfSpawnNext
    #pragma HLS INTERFACE ap_ctrl_none port = return
    #pragma HLS PIPELINE II=1 style=flp

    counter_continuation args = taskIn.read();

    //Check if we need to request data
    if(args.a_cur_ptr >= args.a_storage_top_pointer && args.a_cur_ptr < args.a_full_len){
        //Request more A data
        addr_t closure = closureIn.read();
        memReader_taskOut mem_reader_request;
        mem_reader_request._cont = closure;
        //The spawnNext write buffer replaces this with the metadata assigned by
        //the ArgumentServer before releasing the child.
        mem_reader_request.continuation_meta = 0;
        mem_reader_request.start_addr = args.A + args.a_storage_top_pointer * sizeof(uint32_t);
        mem_reader_request.offset = offsetof(counter_continuation, A_data) / (ADDER_WINDOW * sizeof(uint32_t));

        adder_self_spawn_next self_spawn;
        self_spawn.addr = closure;
        self_spawn.data = args;
        self_spawn.data._counter = 1;
        self_spawn.data.a_storage_top_pointer += ADDER_WINDOW;
        //The fetch is OR-merged into this closure, so the window it lands in has
        //to start clear. B's window rides along untouched.
        for (int i = 0; i < ADDER_WINDOW; i++) {
            #pragma HLS UNROLL
            self_spawn.data.A_data[i] = 0;
        }
        self_spawn.size = 8;
        self_spawn.allow = 1;
        selfSpawnNext.write(self_spawn);
        memReaderTaskOut.write(mem_reader_request);

        return;
    }
    if(args.b_cur_ptr >= args.b_storage_top_pointer && args.b_cur_ptr < args.b_full_len){
        //Request more B data
        addr_t closure = closureIn.read();
        memReader_taskOut mem_reader_request;
        mem_reader_request._cont = closure;
        mem_reader_request.continuation_meta = 0;
        mem_reader_request.start_addr = args.B + args.b_storage_top_pointer * sizeof(uint32_t);
        mem_reader_request.offset = offsetof(counter_continuation, B_data) / (ADDER_WINDOW * sizeof(uint32_t));

        adder_self_spawn_next self_spawn;
        self_spawn.addr = closure;
        self_spawn.data = args;
        self_spawn.data._counter = 1;
        self_spawn.data.b_storage_top_pointer += ADDER_WINDOW;
        for (int i = 0; i < ADDER_WINDOW; i++) {
            #pragma HLS UNROLL
            self_spawn.data.B_data[i] = 0;
        }
        self_spawn.size = 8;
        self_spawn.allow = 1;
        selfSpawnNext.write(self_spawn);
        memReaderTaskOut.write(mem_reader_request);

        return;
    }

    //The data is here. Advance the pointers and, potentially, the counter
    uint32_t a_index = args.a_cur_ptr - (args.a_storage_top_pointer - ADDER_WINDOW);
    uint32_t b_index = args.b_cur_ptr - (args.b_storage_top_pointer - ADDER_WINDOW);
    if(args.A_data[a_index] > args.B_data[b_index]){
        ++args.b_cur_ptr;
    }else if(args.B_data[b_index] > args.A_data[a_index]){
        ++args.a_cur_ptr;
    }else {
        ++args.a_cur_ptr;
        ++args.b_cur_ptr;
        ++args.running_partial_count;
    }

    if(args.a_cur_ptr >= args.a_full_len || args.b_cur_ptr >= args.b_full_len){
        //We are done! Forward the count we came up with
        adder_done_continuation_update done_cont;
        done_cont.address = args._cont;
        done_cont.continuation_meta = args.continuation_meta;
        done_cont.offset = args.offset;
        done_cont.payload = args.running_partial_count;
        argOut.write(done_cont);
    } else{
        //Need to go around again. Nothing was allocated and nothing is waiting on
        //an argument, so this parks on no closure: _counter of 0 tells the write
        //buffer to spawn the task directly, and allow of 0 because there is no
        //memReader task to let out behind it.
        adder_self_spawn_next self_spawn;
        self_spawn.addr = 0;
        self_spawn.data = args;
        self_spawn.data._counter = 0;
        self_spawn.size = 8;
        self_spawn.allow = 0;
        selfSpawnNext.write(self_spawn);
    }

}

//Loop through 32 candidate vertices at a time, spawn tasks for each, and then when their continuation returns, sum their results and launch another 32. Finally
//when all are done, write the result to memory. Uses a single memory port to burst read the target vertex's adjacency list, and to write the final results.

//adder_done_continuation_update contains the current count, and therefore can be used to both launch this task initially and to return from adders. 
//Since adders use a similar trick, remember to initialize all their variables, including their partial-sums to 0.
void adder_unit_launcher(void* mem, hls::stream<adder_unit_launcher_continuation> &taskIn, hls::stream<uint64_t> &closureIn, hls::stream<counter_continuation> &adder_taskOut, hls::stream<adder_unit_launcher_spawn_next> &spawnNext){
    #pragma HLS INTERFACE mode = axis port = taskIn
    #pragma HLS INTERFACE mode = axis port = closureIn
    #pragma HLS INTERFACE mode = axis port = adder_taskOut
    #pragma HLS INTERFACE mode = axis port = spawnNext
    #pragma HLS INTERFACE mode = m_axi port = mem max_widen_bitwidth=256 num_read_outstanding=16
    #pragma HLS INTERFACE ap_ctrl_none port = return
    #pragma HLS PIPELINE off

    adder_unit_launcher_continuation args = taskIn.read();

    //Sum whatever the last batch of adders left behind. Zero on the first entry.
    //Nothing below needs this except the closure we park on, so unroll it into an
    //adder tree and let it settle while the candidate reads are in flight.
    uint32_t total = args.running_total;
    for(int i = 0; i < LAUNCHER_BATCH; i++){
        #pragma HLS UNROLL
        total += args.counts[i];
    }

    if(args.cursor >= args.v_size){
        //The last round handed out the tail of adj(v). Commit this vertex's count
        //and done flag as one 8-byte store, without taking a closure we would only
        //throw away -- the allocator pool is monotonic.
        uint64_t done_word = ((uint64_t)1 << 32) | (uint32_t)total;
        MEM_ARR_OUT(mem, args.triangle_count_arr, args.vertex, uint64_t, done_word);
        return;
    }

    //Hand out the next batch as we find it. The adders sit in the write buffer
    //until the closure below lands, so this runs ahead of the sum and of the
    //spawn packet. A candidate with no neighbours can produce no matches and would
    //leave its adder nothing to compare against, so it does not take a slot.
    addr_t closure = closureIn.read();
    uint32_t collected = 0;
    uint32_t cursor = args.cursor;
    while(cursor < args.v_size && collected < LAUNCHER_BATCH){
        #pragma HLS PIPELINE II=1
        uint32_t u = MEM_ARR_IN(mem, args.v_neighbors, cursor, uint32_t);
        addr_t u_neighbors = MEM_ARR_IN(mem, args.adj_list, u * 2, addr_t);
        uint32_t u_size = MEM_ARR_IN(mem, args.adj_list, u * 2 + 1, uint64_t);
        cursor++;

        if(u_size > 0){
            counter_continuation adder_args;
            //Set by the adder itself once it parks on a fetch.
            adder_args._counter = 0;
            adder_args.offset = offsetof(adder_unit_launcher_continuation, counts) / sizeof(uint32_t) + collected;
            //The spawnNext write buffer replaces this with the metadata it assigns
            //to the closure below.
            adder_args.continuation_meta = 0;
            adder_args.running_partial_count = 0;
            adder_args._cont = closure;
            adder_args.A = args.v_neighbors;
            adder_args.B = u_neighbors;
            adder_args.a_cur_ptr = 0;
            adder_args.a_storage_top_pointer = 0;
            adder_args.a_full_len = args.v_size;
            adder_args.b_cur_ptr = 0;
            adder_args.b_storage_top_pointer = 0;
            adder_args.b_full_len = u_size;
            //Both windows are empty, so the adder's first two passes fetch them.
            //The memReader ORs into these, so they start clear.
            for(int k = 0; k < ADDER_WINDOW; k++){
                #pragma HLS UNROLL
                adder_args.A_data[k] = 0;
                adder_args.B_data[k] = 0;
            }
            adder_taskOut.write(adder_args);
            collected++;
        }
    }

    if(collected == 0){
        //The tail of adj(v) was all empty lists. Nothing is going to release the
        //closure we took, so retire the vertex here and leave it unused.
        uint64_t done_word = ((uint64_t)1 << 32) | (uint32_t)total;
        MEM_ARR_OUT(mem, args.triangle_count_arr, args.vertex, uint64_t, done_word);
        return;
    }

    //Park on the closure. The adders release it once every one of them has dropped
    //its partial count into its own slot.
    adder_unit_launcher_spawn_next launcher_spawn;
    launcher_spawn.addr = closure;
    launcher_spawn.data = args;
    launcher_spawn.data._counter = collected;
    launcher_spawn.data.cursor = cursor;
    launcher_spawn.data.running_total = total;
    //Adder updates are OR-merged into this closure, so every count slot has to
    //start clear.
    for(int i = 0; i < LAUNCHER_BATCH; i++){
        #pragma HLS UNROLL
        launcher_spawn.data.counts[i] = 0;
    }
    launcher_spawn.size = 8;
    launcher_spawn.allow = collected;
    spawnNext.write(launcher_spawn);
}

//This is the main launch point. It loops through all vertices, and spawns instances of adder_unit_launcher, one for each base vertex.
void triangle(void *mem, hls::stream<triangle_task> &taskIn, hls::stream<adder_unit_launcher_continuation> &taskOutGlobal){
    #pragma HLS INTERFACE mode = axis port = taskIn
    #pragma HLS INTERFACE mode = axis port = taskOutGlobal
    #pragma HLS INTERFACE mode = m_axi port = mem
    #pragma HLS INTERFACE ap_ctrl_none port = return
    #pragma HLS PIPELINE off

    triangle_task task = taskIn.read();

    for(int i = 0; i < task.vertex_count; i++){
        #pragma HLS PIPELINE II=1
        addr_t v_neighbors = MEM_ARR_IN(mem, task.adj_list, i * 2, addr_t);
        uint32_t v_size = MEM_ARR_IN(mem, task.adj_list, i * 2 + 1, uint64_t);

        if(v_size == 0){
            //No launcher will ever run for this vertex, so retire its slot here
            //rather than leaving the host to work out which ones to skip.
            MEM_ARR_OUT(mem, task.triangle_count_arr, i, uint64_t, ((uint64_t)1 << 32));
            continue;
        }

        adder_unit_launcher_continuation new_task;
        //Nothing is outstanding and there is nothing to sum on the first round.
        new_task._counter = 0;
        new_task.vertex = i;
        new_task.v_neighbors = v_neighbors;
        new_task.adj_list = task.adj_list;
        new_task.v_size = v_size;
        new_task.cursor = 0;
        new_task.running_total = 0;
        new_task.triangle_count_arr = task.triangle_count_arr;
        for(int k = 0; k < LAUNCHER_BATCH; k++){
            #pragma HLS UNROLL
            new_task.counts[k] = 0;
        }
        taskOutGlobal.write(new_task);
    }
}