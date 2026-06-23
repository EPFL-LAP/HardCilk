#include "ap_int.h"
#include "util.h"
#include <cstdint>
#include <stdint.h>
#include <limits>

#define VERTICES_PER_TASK 64
#define MAX_OUTSTANDING_LOCKS 1

// For BellmanFord, we initialize the following:
// All distances should be initially set to +INF (float), so the first
// relaxation of any reachable vertex always stores a smaller value; the source
// distance is then set to 0 below.
// The graph goes (Location of Vertex) -> [Edges], where each edge contains (weight, destination)
static const uint32_t STREAM_END = 0x80000000u;

void BellmanFord(void *mem_0, hls::stream<sparse_edgemap_helper_args> &taskOutGlobal,
                 hls::stream<BFS_args> &taskIn)
{
#pragma HLS INTERFACE ap_ctrl_none port = return
#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = taskOutGlobal
#pragma HLS INTERFACE mode = m_axi port = mem_0 bundle = gmem channel = \
    0 latency = 48 num_write_outstanding = 16 num_read_outstanding =    \
        16 max_write_burst_length = 16 max_read_burst_length =          \
            16 max_widen_bitwidth = 256

    auto task = taskIn.read();

    bool first_iteration = task.round == 0 && task.frontier_length == 0;
    if (first_iteration)
    {
        MEM_ARR_OUT(mem_0, task.distance, task.source, uint32_t, 0);
        MEM_ARR_OUT(mem_0, task.frontier0, 0, uint32_t, task.source);

        // Force AXI write to HBM for the hardware counter
        MEM_OUT_VOLATILE(mem_0, task.nextFChar, uint64_t, 0);

        task.frontier_length = 1;
        task.active = 0;
        task.done = 0;
        // Single source = one chunk. Must be set here too: leaving counter at
        // the incoming value (0) underflows when the lone helper decrements it,
        // so the continuation would never re-inject.
        task.counter = 1;
    }
    else
    {
        // Force AXI read from HBM for the hardware counter
        uint64_t next_length = MEM_IN_VOLATILE(mem_0, task.nextFChar, uint64_t);

        if (next_length == 0)
        {
            task.done = 1;
            task.counter = 1;
        }
        else
        {
            // Reset the actual counter in HBM
            MEM_OUT_VOLATILE(mem_0, task.nextFChar, uint64_t, 0);

            task.frontier_length = (uint32_t)next_length;
            task.active = 1 - task.active;

            // VERTICES_PER_TASK - 1 to round up rather than down
            uint32_t num_chunks = (task.frontier_length + VERTICES_PER_TASK - 1) / VERTICES_PER_TASK;
            task.counter = num_chunks;
        }
    }

    // Need to do 1 at a time so that the counter gets written last
    MEM_OUT(mem_0, task.cont + 4, uint32_t, task.source);
    MEM_OUT(mem_0, task.cont + 8, uint32_t, task.vertex_count);
    // We continue from the next round
    MEM_OUT(mem_0, task.cont + 12, uint32_t, task.round + 1);
    // MEM_OUT(mem_0, task.cont + 16, uint32_t, task.max_depth);
    MEM_OUT(mem_0, task.cont + 20, uint32_t, task.frontier_length);
    MEM_OUT(mem_0, task.cont + 24, uint32_t, task.active);
    MEM_OUT(mem_0, task.cont + 28, uint32_t, task.done);
    MEM_OUT(mem_0, task.cont + 32, addr_t, task.graph);
    MEM_OUT(mem_0, task.cont + 40, addr_t, task.distance);
    MEM_OUT(mem_0, task.cont + 48, addr_t, task.relaxed);
    MEM_OUT(mem_0, task.cont + 56, addr_t, task.frontier0);
    MEM_OUT(mem_0, task.cont + 64, addr_t, task.frontier1);
    MEM_OUT(mem_0, task.cont + 72, addr_t, task.nextFChar);
    MEM_OUT(mem_0, task.cont + 80, addr_t, task.cont);
    // Otherwise the counter would get written first
    MEM_OUT(mem_0, task.cont, uint32_t, task.counter);

    // If done, exit before spawning helpers
    if (task.done)
        return;

    addr_t current_frontier = task.active == 0 ? task.frontier0 : task.frontier1;
    addr_t next_frontier = task.active == 0 ? task.frontier1 : task.frontier0;

    // Spawn the helper tasks
    for (uint32_t i = 0; i < task.frontier_length; i += VERTICES_PER_TASK)
    {
#pragma HLS PIPELINE II = 1
        sparse_edgemap_helper_args helper_task;
        helper_task.graph = task.graph;
        helper_task.distance = task.distance;
        helper_task.relaxed = task.relaxed;
        helper_task.frontier = current_frontier;
        helper_task.next_frontier = next_frontier;
        helper_task.nextFChar = task.nextFChar;
        helper_task.cont = task.cont;
        helper_task.index = i;
        helper_task.round = task.round;
        // helper_task.max_depth = task.max_depth;
        helper_task.vertex_count = task.vertex_count;
        helper_task.task_vertex_count = (task.frontier_length - i) > VERTICES_PER_TASK ? VERTICES_PER_TASK : (task.frontier_length - i);
        taskOutGlobal.write(helper_task);
    }
}

typedef struct
{
    addr_t neighbor_address;
    uint64_t degree;
    float distance;
} vertex_output;

void read_vertices(void *mem, hls::stream<uint32_t> &output_vertices, sparse_edgemap_helper_args &task)
{
    for (uint32_t i = 0; i < VERTICES_PER_TASK; i++)
    {
#pragma HLS pipeline II = 1

        uint32_t vertex = MEM_ARR_IN(mem, task.frontier, task.index + i, uint32_t);

        if (i < task.task_vertex_count)
        {
            output_vertices.write(vertex);
        }
    }

    // Last vertex sentinel
    output_vertices.write(STREAM_END);
}

// Carries a frontier vertex and its (already fetched) graph bulk entry from the
// relaxed-clear stage to the distance-read stage.
typedef struct
{
    uint32_t vertex;
    addr_t neighbor_address;
    uint64_t degree;
} vertex_bulk;

// load_vertices is split into two dataflow stages on purpose. Clearing
// relaxed[vertex] and reading distance[vertex] live on different m_axi bundles,
// so when they sat in one pipelined loop HLS issued them with no ordering and
// the clear's posted write could land AFTER the distance read. A concurrent
// neighbor relaxing this same vertex would then see relaxed[vertex] still set
// and skip re-enqueueing it, stranding the improved distance (every FPGA
// distance ends up >= the optimum). Splitting forces the clear to be issued in
// stage 1, with stage 1's graph (bulk) read latency + the FIFO sitting between
// it and the distance read in stage 2, so the clear is globally visible before
// the read -- which is exactly the ordering the "read-after-update => no
// re-enqueue" optimization relies on.
void clear_relaxed_load_bulk(void *mem_graph, void *mem_relaxed, hls::stream<uint32_t> &input_vertices, hls::stream<vertex_bulk> &output_bulk, sparse_edgemap_helper_args &task)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        uint32_t vertex = input_vertices.read();
        if (vertex & STREAM_END)
        {
            break;
        }
        ap_uint<128> bulk = MEM_IN(mem_graph, task.graph + ((addr_t)vertex << 4), ap_uint<128>);
        // Reset relaxed for this vertex. The distance read that the "no
        // re-enqueue needed" rule depends on happens in the next stage, after
        // this write has had the bulk-read + FIFO latency to commit.
        MEM_ARR_OUT(mem_relaxed, task.relaxed, vertex, uint8_t, 0);

        vertex_bulk out;
        out.vertex = vertex;
        out.neighbor_address = bulk.range(63, 0);
        out.degree = bulk.range(127, 64);
        output_bulk.write(out);
    }
    vertex_bulk sentinel;
    sentinel.vertex = STREAM_END;
    sentinel.neighbor_address = 0xFFFFFFFF;
    sentinel.degree = 0;
    output_bulk.write(sentinel);
}

void read_distance(void *mem_distance, hls::stream<vertex_bulk> &input_bulk, hls::stream<vertex_output> &output_vertices, sparse_edgemap_helper_args &task)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        vertex_bulk vb = input_bulk.read();
        if (vb.vertex & STREAM_END)
        {
            break;
        }
        // TODO: Could speed this up by storing distances and relaxed in the graph, that way they could all be fetched in one pull. But, because reading neighbors should be the true bottleneck, this is not a priority
        uint32_t distance = MEM_ARR_IN(mem_distance, task.distance, vb.vertex, uint32_t);

        vertex_output output;
        output.neighbor_address = vb.neighbor_address;
        output.degree = vb.degree;
        output.distance = *((float *)&distance);
        output_vertices.write(output);
    }
    vertex_output sentinel;
    sentinel.neighbor_address = 0xFFFFFFFF;
    sentinel.degree = 0;
    output_vertices.write(sentinel);
}

typedef struct
{
    uint32_t neighbor;
    float distance;
    float vertex_current_distance;
} output_neighbors_type;

void read_neighbors(void *mem, hls::stream<vertex_output> &input_vertices, hls::stream<output_neighbors_type> &output_neighbors, sparse_edgemap_helper_args &task)
{
    while (true)
    {
        vertex_output vertex = input_vertices.read();
        if (vertex.neighbor_address == 0xFFFFFFFF)
        {
            break;
        }
        for (uint32_t n = 0; n < vertex.degree; n++)
        {
#pragma HLS pipeline II = 1
            uint64_t weight_and_neighbor = MEM_ARR_IN(mem, vertex.neighbor_address << 1, n, uint64_t);
            uint32_t weight = ((ap_uint<64>)weight_and_neighbor).range(63, 32);
            output_neighbors_type for_next;
            for_next.neighbor = ((ap_uint<64>)weight_and_neighbor).range(31, 0);
            for_next.distance = *((float *)&weight);
            for_next.vertex_current_distance = vertex.distance;
            output_neighbors.write(for_next);
        }
    }
    output_neighbors_type sentinel;
    sentinel.neighbor = STREAM_END;
    output_neighbors.write(sentinel);
}

typedef struct
{
    uint32_t neighbor;
    float new_distance;
} closer_neighbor_type;

void neighbor_visited_check(void *mem, hls::stream<output_neighbors_type> &input_neighbors, hls::stream<closer_neighbor_type> &output_closer_neighbors, sparse_edgemap_helper_args &task)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        output_neighbors_type weight_and_neighbor = input_neighbors.read();
        if (weight_and_neighbor.neighbor & STREAM_END)
        {
            break;
        }
        float current_distance = MEM_ARR_IN(mem, task.distance, weight_and_neighbor.neighbor, float);
        float new_distance = task.round < task.vertex_count ? weight_and_neighbor.distance + weight_and_neighbor.vertex_current_distance : -std::numeric_limits<float>::infinity();
        if (new_distance < current_distance)
        {
            closer_neighbor_type neighbor;
            neighbor.neighbor = weight_and_neighbor.neighbor;
            neighbor.new_distance = new_distance;
            output_closer_neighbors.write(neighbor);
        }
    }
    closer_neighbor_type sentinel;
    sentinel.neighbor = STREAM_END;
    output_closer_neighbors.write(sentinel);
}

void attempt_priority_write(hls::stream<closer_neighbor_type> &input_closer_neighbors, hls::stream<uint32_t> &pw_awaiting_response, sparse_edgemap_helper_args &task, hls::stream<lock_req> &toLock)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        closer_neighbor_type closer_neighbor = input_closer_neighbors.read();
        if (closer_neighbor.neighbor & STREAM_END)
        {
            break;
        }
        // distance[] is a 4-byte float array, so the slot byte address is
        // task.distance + neighbor*4. The parentheses matter: '+' binds tighter
        // than '<<', so the shift must be wrapped to apply to the index.
        //
        // new_distance is a float, but distance[] stores raw float bits and the
        // AMU's SET_IF_LESS compares the stored 32-bit word. Pass the float's bit
        // pattern reinterpreted as an int -- NOT its numeric value, which the
        // ap_uint<64> operand would otherwise truncate (e.g. 5.7f -> 5). The
        // float-compare flag (last arg) makes the AMU order those bits as floats,
        // so negative distances relax correctly.
        uint32_t new_distance_bits = *((uint32_t *)&closer_neighbor.new_distance);
        lock_req req = make_lock_req(task.distance + ((addr_t)closer_neighbor.neighbor << 2), new_distance_bits, LOCK_OP_SET_IF_LESS_AND_RETURN_CURRENT, true, ATOMIC_MODE_WORD, 0, true);
        toLock.write(req);
        pw_awaiting_response.write(closer_neighbor.neighbor);
    }
    pw_awaiting_response.write(STREAM_END);
}
void listen_priority_write_response(hls::stream<uint32_t> &pw_awaiting_response, hls::stream<uint32_t> &successful_pw, sparse_edgemap_helper_args &task, hls::stream<lock_resp> &fromLock, hls::stream<lock_req> &toLock)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        uint32_t _token = pw_awaiting_response.read();
        if (_token & STREAM_END)
        {
            break;
        }
        // Responses may arrive out of order, so recover which neighbor this is
        // from the echoed tag instead of pairing by FIFO order. The lock address
        // was task.distance + (neighbor << 2) (distance is a 4-byte float array),
        // so subtract the base and shift back down to recover the index.
        lock_resp resp = fromLock.read();
        ap_uint<64> tag = lock_resp_tag(resp);
        // write_occurred == 1 means the SET_IF_LESS actually stored, i.e. we
        // shortened this neighbor's distance, so it belongs in the next frontier.
        if (lock_resp_success(resp) && lock_resp_write_occurred(resp) != 0)
        {
            uint32_t neighbor = (tag - task.distance) >> 2;
            successful_pw.write(neighbor);
            // Now we test and set
            lock_req req = make_lock_req(task.relaxed + neighbor, 1, LOCK_OP_SET_AND_RETURN_CURRENT, false, ATOMIC_MODE_BYTE);
            toLock.write(req);
        }
    }
    successful_pw.write(STREAM_END);
}

void recieve_test_and_set_responses(void *mem, hls::stream<uint32_t> &successful_pw, hls::stream<uint32_t> &successful_ts, sparse_edgemap_helper_args &task, hls::stream<lock_resp> &fromLock, hls::stream<lock_req> &toLock2)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        uint32_t _token = successful_pw.read();
        if (_token & STREAM_END)
        {
            break;
        }
        // Responses may arrive out of order, so recover which neighbor this is
        // from the echoed tag instead of pairing by FIFO order. The lock address
        // was task.relaxed + neighbor (relaxed is a byte array, one byte per
        // slot), so subtract the base to get the index.
        lock_resp resp = fromLock.read();
        ap_uint<64> tag = lock_resp_tag(resp);
        uint32_t neighbor = (uint32_t)(tag - (ap_uint<64>)task.relaxed);
        if (lock_resp_success(resp) && lock_resp_current_byte(resp) == 0)
        {
            successful_ts.write(neighbor);
            lock_req req = make_lock_req(task.nextFChar, 1, LOCK_OP_ADD_N_RETURN_CURRENT, true, ATOMIC_MODE_DOUBLEWORD);
            toLock2.write(req);
        }
    }
    successful_ts.write(STREAM_END);
}

void write_to_frontier(void *mem, hls::stream<uint32_t> &input_successful_ts, sparse_edgemap_helper_args &task, hls::stream<lock_resp> &fromLock2, hls::stream<uint64_t> &argOut)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        uint32_t neighbor = input_successful_ts.read();
        if (neighbor & STREAM_END)
        {
            break;
        }
        // The add-N response only carries the allocated slot (its previous
        // counter value); every second lock shares the same tag (task.nextFChar),
        // so the tag can't identify the neighbor -- it comes from the FIFO. Any
        // reorder between the two is harmless: each neighbor still lands in a
        // unique slot, so next_frontier ends up holding the same set.
        lock_resp resp = fromLock2.read();
        if (lock_resp_success(resp))
        {
            uint32_t nextFChar = lock_resp_current(resp);
            MEM_ARR_OUT(mem, task.next_frontier, nextFChar, uint32_t, neighbor);
        }
    }

    bool sent = false;
    while (!sent)
    {
#pragma HLS PIPELINE off
        sent = argOut.write_nb(task.cont);
    }
}

// ---------------------------------------------------------
// Top Level Edge Map Helper
// ---------------------------------------------------------

void sparse_edgemap_helper(void *mem_0, void *mem_1, void *mem_2, void *mem_3, void *mem_4, void *mem_5, void *mem_6,
                           hls::stream<sparse_edgemap_helper_args> &taskIn,
                           hls::stream<uint64_t> &argOut,
                           hls::stream<lock_req> &toLock0,
                           hls::stream<lock_resp> &fromLock0,
                           hls::stream<lock_req> &toLock1,
                           hls::stream<lock_resp> &fromLock1,
                           hls::stream<lock_req> &toLock2,
                           hls::stream<lock_resp> &fromLock2)
{
#pragma HLS INTERFACE ap_ctrl_none port = return

#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = argOut
#pragma HLS INTERFACE mode = axis port = toLock0
#pragma HLS INTERFACE mode = axis port = fromLock0
#pragma HLS INTERFACE mode = axis port = toLock1
#pragma HLS INTERFACE mode = axis port = fromLock1
#pragma HLS INTERFACE mode = axis port = toLock2
#pragma HLS INTERFACE mode = axis port = fromLock2

#pragma HLS INTERFACE mode = m_axi port = mem_0 bundle = gmem channel = \
    0 latency = 48 num_write_outstanding = 1 num_read_outstanding =     \
        64 max_read_burst_length = 16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_1 bundle = gmem channel = \
    1 latency = 48 num_write_outstanding = 1 num_read_outstanding =     \
        64 max_read_burst_length = 16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_2 bundle = gmem channel = \
    2 latency = 48 num_write_outstanding = 16 num_read_outstanding =    \
        16 max_write_burst_length = 16 max_read_burst_length =          \
            16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_3 bundle = gmem channel = \
    3 latency = 48 num_write_outstanding = 16 num_read_outstanding =    \
        16 max_write_burst_length = 16 max_read_burst_length =          \
            16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_4 bundle = gmem channel = \
    4 latency = 48 num_write_outstanding = 16 num_read_outstanding =    \
        16 max_write_burst_length = 16 max_read_burst_length =          \
            16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_5 bundle = gmem channel = \
    5 latency = 48 num_write_outstanding = 16 num_read_outstanding =    \
        16 max_write_burst_length = 16 max_read_burst_length =          \
            16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_6 bundle = gmem channel = \
    6 latency = 48 num_write_outstanding = 16 num_read_outstanding =    \
        16 max_write_burst_length = 16 max_read_burst_length =          \
            16 max_widen_bitwidth = 256

    auto task = taskIn.read();

    hls::stream<uint32_t> read_vertices_out("read_vertices_out");
    hls::stream<vertex_bulk> loaded_bulk("loaded_bulk");
    hls::stream<vertex_output> output_vertices("output_vertices");
    hls::stream<output_neighbors_type> output_neighbors("output_neighbors");
    hls::stream<closer_neighbor_type> output_closer_neighbors("output_closer_neighbors");
    hls::stream<uint32_t> pw_awaiting_response("pw_awaiting_response");
    hls::stream<uint32_t> successful_pw("successful_pw");
    hls::stream<uint32_t> successful_ts("successful_ts");

    // Setting deep FIFOs to mask latency and prevent lock server deadlocks
#pragma HLS STREAM variable = read_vertices_out depth = 64
#pragma HLS STREAM variable = loaded_bulk depth = 64
#pragma HLS STREAM variable = output_vertices depth = 64
#pragma HLS STREAM variable = output_neighbors depth = 256
#pragma HLS STREAM variable = output_closer_neighbors depth = 256
#pragma HLS STREAM variable = pw_awaiting_response depth = 128
#pragma HLS STREAM variable = successful_pw depth = 128
#pragma HLS STREAM variable = successful_ts depth = 128

#pragma HLS DATAFLOW
    // Per-neighbor pipeline. Three lock round-trips, one per lock channel:
    //   1. priority write  : SET_IF_LESS on distance[]  (toLock0 / fromLock0)
    //   2. relaxed dedup    : test-and-set on relaxed[]  (toLock1 / fromLock1)
    //   3. frontier slot    : add-N on nextFChar         (toLock2 / fromLock2)
    read_vertices(mem_0, read_vertices_out, task);
    // load_vertices split in two so the relaxed[] clear (stage 1) is ordered
    // before the distance[] read (stage 2) -- see comment on the functions.
    clear_relaxed_load_bulk(mem_1, mem_2, read_vertices_out, loaded_bulk, task);
    read_distance(mem_3, loaded_bulk, output_vertices, task);
    read_neighbors(mem_4, output_vertices, output_neighbors, task);
    neighbor_visited_check(mem_5, output_neighbors, output_closer_neighbors, task);
    attempt_priority_write(output_closer_neighbors, pw_awaiting_response, task, toLock0);
    listen_priority_write_response(pw_awaiting_response, successful_pw, task, fromLock0, toLock1);
    recieve_test_and_set_responses(mem_6, successful_pw, successful_ts, task, fromLock1, toLock2);
    write_to_frontier(mem_6, successful_ts, task, fromLock2, argOut);
}
