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
#include "hls_burst_maxi.h"

//window_beat lives in util.h: one window is one AXI beat. The alternative, 16
//scalar reads of the same port, schedules as 16 dependent accesses (measured
//II=29 against a target of 1).


//memReader reads 16 elements at a time into the output argument
void memReader(hls::burst_maxi<window_beat> mem, hls::stream<memReader_task> &taskIn, hls::stream<counter_continuation_update> &argOut) {
    #pragma HLS INTERFACE mode = axis port = taskIn
    #pragma HLS INTERFACE mode = axis port = argOut
    //offset=off keeps the base pointer out of a control register. Without it the
    //burst_maxi object exposes its base as a scalar, which drags in an
    //s_axi_control slave the surrounding architecture does not wire up.
    //bundle=gmem is not cosmetic: the generator reserves HBM ports by looking for
    //a master called m_axi_gmem, and naming the bundle explicitly stops it from
    //defaulting to the argument name.
    #pragma HLS INTERFACE mode = m_axi port = mem bundle = gmem offset = off num_read_outstanding=16
    #pragma HLS INTERFACE ap_ctrl_none port = return
    #pragma HLS PIPELINE II=1 style=flp

    memReader_task args = taskIn.read();
    counter_continuation_update update;
    update.address = args._cont;
    update.continuation_meta = args.continuation_meta;
    update.offset = args.offset;

    //read_request counts in elements, and one element is one window, so a list
    //has to start on a window boundary. a_storage_top_pointer only ever advances
    //by ADDER_WINDOW, so the base address is the only thing the host must align.
    //
    //The tail of a list is fetched as a full window, so this runs off the end of
    //the last adjacency list by up to ADDER_WINDOW - 1 elements. The adder never
    //compares those, but the host still has to pad the neighbour array by one
    //window so the burst stays inside the buffer.
    mem.read_request(args.start_addr / (ADDER_WINDOW * sizeof(uint32_t)), 1);
    window_beat beat = mem.read();

    for (int i = 0; i < ADDER_WINDOW; i++) {
        #pragma HLS UNROLL
        update.payload[i] = beat.range(32 * i + 31, 32 * i);
    }

    argOut.write(update);
}

//taskOutGlobal1 is gated by the write buffer, so a memReader never reaches its
//scheduler before the closure it is about to fill exists. spawnNextLocal is the
//ungated way back into this PE's own queue, for the passes that allocate nothing.
void adder(hls::stream<counter_continuation> &taskIn, hls::stream<adder_done_continuation_update> &argOut, hls::stream<uint64_t> &closureIn, hls::stream<memReader_taskOut> &taskOutGlobal1, hls::stream<adder_self_spawn_next> &spawnNext, hls::stream<counter_continuation> &spawnNextLocal){
    #pragma HLS INTERFACE mode = axis port = taskIn
    #pragma HLS INTERFACE mode = axis port = argOut
    #pragma HLS INTERFACE mode = axis port = closureIn
    #pragma HLS INTERFACE mode = axis port = taskOutGlobal1
    #pragma HLS INTERFACE mode = axis port = spawnNext
    #pragma HLS INTERFACE mode = axis port = spawnNextLocal
    #pragma HLS INTERFACE ap_ctrl_none port = return
    #pragma HLS PIPELINE II=1 style=flp

    counter_continuation args = taskIn.read();

    //Everything the post-comparison decisions need, derived from the INCOMING
    //pointers so it settles in parallel with the window read rather than behind
    //it. Vitis reported two 32-bit icmps back to back on the critical path (1.76
    //ns of a 2.433 ns budget) because the done and refill tests consumed the
    //INCREMENTED cursor. An increment is 0 or 1, so "will this step take it past
    //the end" is knowable up front, and the comparison only has to AND into it.
    //One subtract per side up front, after which every test is a compare against
    //0 or 1 -- a few gates, not another 32-bit operation.
    //
    //Both earlier spellings cost two serial 32-bit ops. (cursor + 1 >= limit)
    //gave add -> icmp, 1.76 ns of a 2.433 ns budget. Moving the arithmetic to the
    //limit, (cursor >= limit - 1), gave exactly the same thing: this is a
    //PIPELINED FUNCTION, not a loop, so every invocation reads a fresh task and
    //both operands land together on the taskIn read. Neither side is invariant,
    //so there is nothing to hoist -- the only fix is to stop needing two ops.
    //
    //Signed: the window difference is <= 0 before the first fetch. Graph lists
    //are nowhere near 2^31, so the cast cannot overflow.
    //A cursor never passes its limit or its window top, so both differences are
    //>= 0 and every test below is "are these bits zero" -- an OR-reduce, a couple
    //of gates. Spelled as magnitude compares (a_left > 0, a_left <= 1) HLS built
    //a full 32-bit comparator for each, 0.880 ns apiece, in series behind the
    //subtract. (~1u) clears bit 0 so "<= 1" is the same shape as "== 0".
    const uint32_t a_left     = args.a_full_len            - args.a_cur_ptr;
    const uint32_t b_left     = args.b_full_len            - args.b_cur_ptr;
    const uint32_t a_win_left = args.a_storage_top_pointer - args.a_cur_ptr;
    const uint32_t b_win_left = args.b_storage_top_pointer - args.b_cur_ptr;

    const bool a_needs = (a_win_left == 0) && (a_left != 0);
    const bool b_needs = (b_win_left == 0) && (b_left != 0);
    const bool skip = a_needs || b_needs;

    const bool a_done_now  = (a_left     == 0);
    const bool b_done_now  = (b_left     == 0);
    const bool a_done_next = ((a_left     & ~1u) == 0);
    const bool b_done_next = ((b_left     & ~1u) == 0);
    const bool a_wrap_next = ((a_win_left & ~1u) == 0);
    const bool b_wrap_next = ((b_win_left & ~1u) == 0);

    //The window base is always a multiple of ADDER_WINDOW -- a_storage_top_pointer
    //starts at 0 and only ever advances by ADDER_WINDOW -- so the in-window index
    //is just the low bits of the cursor. A bit-select, not a 0.708 ns subtract.
    const uint32_t a_index = args.a_cur_ptr & (ADDER_WINDOW - 1);
    const uint32_t b_index = args.b_cur_ptr & (ADDER_WINDOW - 1);

    bool inc_a = false, inc_b = false, hit = false;
    if(!skip){
        const uint32_t av = args.A_data[a_index];
        const uint32_t bv = args.B_data[b_index];
        if(av > bv){
            inc_b = true;
        }else if(bv > av){
            inc_a = true;
        }else{
            inc_a = true; inc_b = true; hit = true;
        }
    }

    if(inc_a) ++args.a_cur_ptr;
    if(inc_b) ++args.b_cur_ptr;
    if(hit)   ++args.running_partial_count;

    const bool done     = a_done_now || b_done_now ||
                          (inc_a && a_done_next) || (inc_b && b_done_next);
    const bool refill_a = inc_a ? (a_wrap_next && !a_done_next) : a_needs;
    const bool refill_b = inc_b ? (b_wrap_next && !b_done_next) : b_needs;

    if(done){
        //We are done! Forward the count we came up with
        adder_done_continuation_update done_cont;
        done_cont.address = args._cont;
        done_cont.continuation_meta = args.continuation_meta;
        done_cont.offset = args.offset;
        done_cont.payload = args.running_partial_count;
        argOut.write(done_cont);
        return;
    } else{
        //Need to go around again. This pass allocated nothing and is waiting on
        //nothing, so it goes straight back into this PE's own queue instead of
        //through the write buffer, which only ever releases a task once a closure
        //write it has no reason to make comes back.
    }


    //Check if we need to request data
    if(refill_a){
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
        self_spawn.size = 7; // log2(sizeof(counter_continuation))
        self_spawn.allow = 1;
        spawnNext.write(self_spawn);
        taskOutGlobal1.write(mem_reader_request);

        return;
    }
    if(refill_b){
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
        self_spawn.size = 7; // log2(sizeof(counter_continuation))
        self_spawn.allow = 1;
        spawnNext.write(self_spawn);
        taskOutGlobal1.write(mem_reader_request);

        return;
    }
    spawnNextLocal.write(args);

}

//Loop through 32 candidate vertices at a time, spawn tasks for each, and then when their continuation returns, sum their results and launch another 32. Finally
//when all are done, write the result to memory. Uses a single memory port to burst read the target vertex's adjacency list, and to write the final results.

//adder_unit_launcher_continuation carries the running count, and therefore can be used to both launch this task initially and to return from adders.
//Since adders use a similar trick, remember to initialize all their variables, including their partial-sums to 0.
void adder_unit_launcher(void* mem, hls::stream<adder_unit_launcher_continuation> &taskIn, hls::stream<adder_unit_launcher_done_update> &argOut, hls::stream<uint64_t> &closureIn, hls::stream<counter_continuation> &taskOutGlobal1, hls::stream<adder_unit_launcher_spawn_next> &spawnNext){
    #pragma HLS INTERFACE mode = axis port = taskIn
    #pragma HLS INTERFACE mode = axis port = argOut
    #pragma HLS INTERFACE mode = axis port = closureIn
    #pragma HLS INTERFACE mode = axis port = taskOutGlobal1
    #pragma HLS INTERFACE mode = axis port = spawnNext
    //offset=off: these kernels address HBM absolutely from the task, so the base
    //register only adds an s_axi_control slave nothing drives.
    #pragma HLS INTERFACE mode = m_axi port = mem bundle = gmem offset = off max_widen_bitwidth=256 num_read_outstanding=16
    #pragma HLS INTERFACE ap_ctrl_none port = return
    #pragma HLS PIPELINE off
    //The neighbour list, the adjacency index and the result array are disjoint
    //buffers reached through one port, so the inter-iteration dependence HLS
    //assumes between them does not exist.
    #pragma HLS DEPENDENCE variable = mem type = inter dependent = false

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
        //The last round handed out the tail of adj(v). Report the total into the
        //writeback closure triangle parked for this vertex; releasing that closure
        //is what returns the vertex's admission token to the pool, which is why
        //this path takes no closure of its own.
        adder_unit_launcher_done_update done;
        done.address = args._cont;
        done.continuation_meta = args.continuation_meta;
        done.offset = offsetof(vertex_writeback_continuation, count) / sizeof(uint32_t);
        done.payload = total;
        argOut.write(done);
        return;
    }

    //Scanning and emitting have to be separate loops. Filtering inside the read
    //loop makes the batch index depend on the size just fetched, and that index
    //is also the loop bound and the count slot -- a carried dependence through
    //the AXI response, which serialises the loop at the read latency (measured
    //II=141, 147MHz). The scan below has a fixed trip count and no such
    //dependence, so it pipelines; the compaction after it touches no memory, so
    //its dependence on collected is one compare and one increment.
    uint32_t candidates[LAUNCHER_BATCH];
    adj_entry_beat raw[LAUNCHER_BATCH];
    addr_t u_neighbors[LAUNCHER_BATCH];
    uint32_t u_sizes[LAUNCHER_BATCH];

    uint32_t collected = 0;
    uint32_t cursor = args.cursor;

    //A whole batch of candidates can be neighbourless, which says nothing about
    //the ones after them, so keep scanning until something turns up or adj(v)
    //runs out. This loop is not pipelined; the two inside it are.
    while(cursor < args.v_size && collected == 0){
        uint32_t scanned = args.v_size - cursor;
        if(scanned > LAUNCHER_BATCH)
            scanned = LAUNCHER_BATCH;

        //Walking adj(v) and looking each candidate up in the index are separate
        //loops on purpose. Together they are two bus accesses per iteration on
        //one port, which caps the loop at II=2 however they are written; apart,
        //each is one access per iteration and pipelines at II=1. The lookup is
        //the random-access one, so it is the one that must not stall.
        for(int k = 0; k < scanned; k++){
            #pragma HLS PIPELINE II=1
            candidates[k] = MEM_ARR_IN(mem, args.v_neighbors, cursor + k, uint32_t);
        }

        for(int k = 0; k < scanned; k++){
            #pragma HLS PIPELINE II=1
            //Both fields of the index entry in one 16-byte beat.
            raw[k] = MEM_ARR_IN(mem, args.adj_list, candidates[k], adj_entry_beat);
        }
        cursor += scanned;

        //A candidate with no neighbours can produce no matches and would leave
        //its adder nothing to compare against, so it does not take a slot.
        for(int k = 0; k < scanned; k++){
            #pragma HLS PIPELINE II=1
            if(adjSize(raw[k]) > 0){
                u_neighbors[collected] = adjNeighbors(raw[k]);
                u_sizes[collected] = adjSize(raw[k]);
                collected++;
            }
        }
    }

    if(collected == 0){
        //adj(v) ran out with nothing usable left, and no closure was taken.
        adder_unit_launcher_done_update done;
        done.address = args._cont;
        done.continuation_meta = args.continuation_meta;
        done.offset = offsetof(vertex_writeback_continuation, count) / sizeof(uint32_t);
        done.payload = total;
        argOut.write(done);
        return;
    }

    //Only now, with a batch that is certainly non-empty, claim a closure. The
    //pool is monotonic, so one taken and not used is gone for the rest of the run.
    addr_t closure = closureIn.read();

    for(int i = 0; i < collected; i++){
        #pragma HLS PIPELINE II=1
        counter_continuation adder_args;
        //Set by the adder itself once it parks on a fetch.
        adder_args._counter = 0;
        adder_args.offset = offsetof(adder_unit_launcher_continuation, counts) / sizeof(uint32_t) + i;
        //The spawnNext write buffer replaces this with the metadata it assigns
        //to the closure below.
        adder_args.continuation_meta = 0;
        adder_args.running_partial_count = 0;
        adder_args._cont = closure;
        adder_args.A = args.v_neighbors;
        adder_args.B = u_neighbors[i];
        adder_args.a_cur_ptr = 0;
        adder_args.a_storage_top_pointer = 0;
        adder_args.a_full_len = args.v_size;
        adder_args.b_cur_ptr = 0;
        adder_args.b_storage_top_pointer = 0;
        adder_args.b_full_len = u_sizes[i];
        //Both windows are empty, so the adder's first two passes fetch them.
        //The memReader ORs into these, so they start clear.
        for(int k = 0; k < ADDER_WINDOW; k++){
            #pragma HLS UNROLL
            adder_args.A_data[k] = 0;
            adder_args.B_data[k] = 0;
        }
        taskOutGlobal1.write(adder_args);
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
    launcher_spawn.size = 7; // log2(sizeof(adder_unit_launcher_continuation))
    launcher_spawn.allow = collected;
    spawnNext.write(launcher_spawn);
}

//This is the main launch point. It loops through all vertices, and spawns instances of adder_unit_launcher, one for each base vertex.
void triangle(void *mem, hls::stream<triangle_task> &taskIn, hls::stream<uint64_t> &closureIn, hls::stream<adder_unit_launcher_continuation> &taskOutGlobal, hls::stream<triangle_spawn_next> &spawnNext){
    #pragma HLS INTERFACE mode = axis port = taskIn
    #pragma HLS INTERFACE mode = axis port = closureIn
    #pragma HLS INTERFACE mode = axis port = taskOutGlobal
    #pragma HLS INTERFACE mode = axis port = spawnNext
    #pragma HLS INTERFACE mode = m_axi port = mem bundle = gmem offset = off
    #pragma HLS INTERFACE ap_ctrl_none port = return
    #pragma HLS PIPELINE off
    //The loop reads the adjacency index and writes the result array through the
    //same port, so HLS orders every read behind the previous write and the loop
    //serialises at the AXI latency. The two live in disjoint buffers the host
    //allocates separately, so that dependence is genuinely false.
    #pragma HLS DEPENDENCE variable = mem type = inter dependent = false

    triangle_task task = taskIn.read();

    const uint32_t last_vertex = task.first_vertex + task.vertex_count;
    for(uint32_t i = task.first_vertex; i < last_vertex; i++){
        #pragma HLS PIPELINE II=1
        //Both fields of the index entry in one 16-byte beat.
        adj_entry_beat entry = MEM_ARR_IN(mem, task.adj_list, i, adj_entry_beat);
        addr_t v_neighbors = adjNeighbors(entry);
        uint32_t v_size = adjSize(entry);

        if(v_size == 0){
            //No launcher will ever run for this vertex, so retire its slot here
            //rather than leaving the host to work out which ones to skip.
            MEM_ARR_OUT(mem, task.triangle_count_arr, i, uint64_t, ((uint64_t)1 << 32));
            continue;
        }

        //THE ADMISSION CAP. One closure per vertex that will actually run, taken
        //before the launcher is spawned and returned only when the vertex retires,
        //so the size of this pool is exactly how many vertices may be in flight.
        //Blocking here is safe precisely because triangle is a pure source: it
        //consumes nothing from downstream, so a stalled root cannot be holding up
        //whatever would free the closure it is waiting for.
        addr_t closure = closureIn.read();

        //Parked BEFORE the launcher is spawned, so the closure the launcher is
        //about to report into already exists. With no spawnNext write buffer on
        //this task there is nothing else enforcing that order.
        triangle_spawn_next park;
        park.addr = closure;
        park.data._counter = 1; //the one launcher below
        park.data._padding0 = 0;
        //Replaced by the metadata the ArgumentServer assigns to this closure.
        park.data.continuation_meta = 0;
        //The launcher's update is OR-merged in, so the slot has to start clear.
        park.data.count = 0;
        park.data.triangle_count_arr = task.triangle_count_arr;
        park.data.vertex = i;
        park.data._padding1 = 0;
        park.size = 7; //log2(sizeof(vertex_writeback_continuation))
        park.allow = 1;
        spawnNext.write(park);

        adder_unit_launcher_continuation new_task;
        //Nothing is outstanding and there is nothing to sum on the first round.
        new_task._counter = 0;
        new_task.v_size = v_size;
        //The spawnNext write buffer replaces this with the metadata it assigned
        //to the closure above.
        new_task.continuation_meta = 0;
        new_task.cursor = 0;
        new_task._cont = closure;
        new_task.v_neighbors = v_neighbors;
        new_task.adj_list = task.adj_list;
        new_task.running_total = 0;
        for(int k = 0; k < LAUNCHER_BATCH; k++){
            #pragma HLS UNROLL
            new_task.counts[k] = 0;
        }
        taskOutGlobal.write(new_task);
    }
}

//The last hop of a vertex: take the total its launcher reported, commit it, and
//by resolving let the allocator hand this vertex's admission token to the next
//one. Deliberately tiny and deliberately without a closureIn of its own -- it
//allocates nothing, so it can never be the thing that runs out.
void vertexWriteback(void *mem, hls::stream<vertex_writeback_continuation> &taskIn){
    #pragma HLS INTERFACE mode = axis port = taskIn
    #pragma HLS INTERFACE mode = m_axi port = mem bundle = gmem offset = off
    #pragma HLS INTERFACE ap_ctrl_none port = return
    #pragma HLS PIPELINE II=1 style=flp

    vertex_writeback_continuation args = taskIn.read();

    //Same 8-byte {done, count} word the launcher used to store itself.
    uint64_t done_word = ((uint64_t)1 << 32) | (uint64_t)args.count;
    MEM_ARR_OUT(mem, args.triangle_count_arr, args.vertex, uint64_t, done_word);
}