#include "ap_int.h"
#include "util.h"
#include <cstdint>
#include <stdint.h>

#define VERTICES_PER_TASK 64
#define MAX_OUTSTANDING_LOCKS 1


static const uint32_t STREAM_END = 0x80000000u;

void BFS(void *mem_0, hls::stream<sparse_edgemap_helper_args> &taskOutGlobal,
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

    bool first_iteration = task.currentDistance == 0 && task.frontier_length == 0;
    if (first_iteration)
    {
        MEM_ARR_OUT(mem_0, task.distance, task.source, int32_t, 0);
        MEM_ARR_OUT(mem_0, task.visited, task.source, uint8_t, 1);
        MEM_ARR_OUT(mem_0, task.frontier0, 0, uint32_t, task.source);

        // Force AXI write to HBM for the hardware counter
        MEM_OUT_VOLATILE(mem_0, task.nextFChar, uint64_t, 0);

        task.currentDistance = 1;
        task.frontier_length = 1;
        task.active = 0;
        task.done = 0;
        task.counter = 1;
    }
    else
    {
        // Force AXI read from HBM for the hardware counter
        uint64_t next_length = MEM_IN_VOLATILE(mem_0, task.nextFChar, uint64_t);

        if (next_length == 0 || task.currentDistance > task.max_depth)
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
            task.currentDistance++;

            // VERTICES_PER_TASK - 1 to round up rather than down
            uint32_t num_chunks = (task.frontier_length + VERTICES_PER_TASK - 1) / VERTICES_PER_TASK;
            task.counter = num_chunks;
        }
    }

    // Need to do 1 at a time so that the counter gets written last
    MEM_OUT(mem_0, task.cont + 4, uint32_t, task.source);
    MEM_OUT(mem_0, task.cont + 8, uint32_t, task.vertex_count);
    MEM_OUT(mem_0, task.cont + 12, uint32_t, task.currentDistance);
    MEM_OUT(mem_0, task.cont + 16, uint32_t, task.max_depth);
    MEM_OUT(mem_0, task.cont + 20, uint32_t, task.frontier_length);
    MEM_OUT(mem_0, task.cont + 24, uint32_t, task.active);
    MEM_OUT(mem_0, task.cont + 28, uint32_t, task.done);
    MEM_OUT(mem_0, task.cont + 32, addr_t, task.graph);
    MEM_OUT(mem_0, task.cont + 40, addr_t, task.distance);
    MEM_OUT(mem_0, task.cont + 48, addr_t, task.visited);
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
        helper_task.visited = task.visited;
        helper_task.frontier = current_frontier;
        helper_task.next_frontier = next_frontier;
        helper_task.nextFChar = task.nextFChar;
        helper_task.cont = task.cont;
        helper_task.index = i;
        helper_task.currentDistance = task.currentDistance;
        helper_task.max_depth = task.max_depth;
        helper_task.vertex_count = task.vertex_count;
        helper_task.task_vertex_count = (task.frontier_length - i) > VERTICES_PER_TASK ? VERTICES_PER_TASK : (task.frontier_length - i);
        taskOutGlobal.write(helper_task);
    }
}

typedef struct
{
    addr_t neighbor_address;
    uint64_t degree;
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

void load_vertices(void *mem, hls::stream<uint32_t> &input_vertices, hls::stream<vertex_output> &output_vertices, sparse_edgemap_helper_args &task)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        uint32_t vertex = input_vertices.read();
        if (vertex & STREAM_END)
        {
            break;
        }
        vertex_output output;
        ap_uint<128> bulk = MEM_IN(mem, task.graph + ((addr_t)vertex << 4), ap_uint<128>);
        output.neighbor_address = bulk.range(63, 0);
        output.degree = bulk.range(127, 64);
        output_vertices.write(output);
    }
    vertex_output sentinel;
    sentinel.neighbor_address = 0xFFFFFFFF;
    sentinel.degree = 0;
    output_vertices.write(sentinel);
}

void read_neighbors(void *mem, hls::stream<vertex_output> &input_vertices, hls::stream<uint32_t> &output_neighbors, sparse_edgemap_helper_args &task)
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
            uint32_t neighbor = MEM_ARR_IN(mem, vertex.neighbor_address, n, uint32_t);
            output_neighbors.write(neighbor);
        }
    }
    output_neighbors.write(STREAM_END);
}

void neighbor_visited_check(void *mem, hls::stream<uint32_t> &input_neighbors, hls::stream<uint32_t> &output_unvisited_neighbors, sparse_edgemap_helper_args &task)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        uint32_t neighbor = input_neighbors.read();
        if (neighbor & STREAM_END)
        {
            break;
        }
        uint8_t visited = MEM_ARR_IN(mem, task.visited, neighbor, uint8_t);
        if (visited == 0)
        {
            output_unvisited_neighbors.write(neighbor);
        }
    }
    output_unvisited_neighbors.write(STREAM_END);
}

void attempt_test_and_set(hls::stream<uint32_t> &input_unvisited_neighbors, hls::stream<uint32_t> &output_awaiting_responses, sparse_edgemap_helper_args &task, hls::stream<lock_req> &toLock)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        uint32_t neighbor = input_unvisited_neighbors.read();
        if (neighbor & STREAM_END)
        {
            break;
        }
        lock_req req = make_lock_req(task.visited + (addr_t)neighbor, 1, LOCK_OP_SET_AND_RETURN_CURRENT, false, ATOMIC_MODE_BYTE);
        toLock.write(req);
        output_awaiting_responses.write(neighbor);
    }
    output_awaiting_responses.write(STREAM_END);
}

void recieve_test_and_set_responses(void *mem, hls::stream<uint32_t> &input_awaiting_response, hls::stream<uint32_t> &successful_ts, sparse_edgemap_helper_args &task, hls::stream<lock_resp> &fromLock, hls::stream<lock_req> &toLock2)
{
    // Fancy optimization suggested by AI:
    //     Address-math hoist: distance[neighbor] is at mem + task.distance +
    //     neighbor*4, and neighbor = tag - task.visited (the visited array is
    //     byte-addressed, one byte per slot). So distance[neighbor] is also
    //     mem + (task.distance - task.visited*4) + tag*4.
    //     The parenthesised term is loop-invariant, so fold it out here; inside the
    //     loop the store address is just distance_base + tag*4 (a shift plus one
    //     add). That keeps the 32-bit tag subtraction off the store's address path
    //     -- it still runs for successful_ts below, but that's a parallel FIFO
    //     write, not the critical path (HLS 200-871 / 200-1016).
    const addr_t distance_base = task.distance - ((addr_t)task.visited << 2);
    while (true)
    {
#pragma HLS pipeline II = 1
        uint32_t _token = input_awaiting_response.read();
        if (_token & STREAM_END)
        {
            break;
        }
        // Responses may arrive out of order, so recover which neighbor this is
        // from the echoed tag instead of pairing by FIFO order. The lock address
        // was task.visited + neighbor, so subtract the base to get the index.
        lock_resp resp = fromLock.read();
        ap_uint<64> tag = lock_resp_tag(resp);
        uint32_t neighbor = (uint32_t)(tag - (ap_uint<64>)task.visited);
        if (lock_resp_success(resp) && lock_resp_current_byte(resp) == 0)
        {
            *((int32_t *)((uint8_t *)mem + distance_base + ((addr_t)tag << 2))) = task.currentDistance;
            successful_ts.write(neighbor);
            lock_req req = make_lock_req(task.nextFChar, 1, LOCK_OP_ADD_N_RETURN_CURRENT, true, ATOMIC_MODE_DOUBLEWORD);
            toLock2.write(req);
        }
    }
    successful_ts.write(STREAM_END);
}

void write_to_frontier(void *mem, hls::stream<uint32_t> &input_successful_ts, sparse_edgemap_helper_args &task, hls::stream<lock_resp> &fromLock2, hls::stream<uint64_t> &argOut)
{
    uint32_t last_slot = 0;
    bool wrote_any = false;
    while (true)
    {
#pragma HLS pipeline II = 1
        uint32_t neighbor = input_successful_ts.read();
        if (neighbor & STREAM_END)
        {
            break;
        }
        // Ordering is irrelevant for the ADD-1
        lock_resp resp = fromLock2.read();
        if (lock_resp_success(resp))
        {
            uint32_t nextFChar = lock_resp_current(resp);
            MEM_ARR_OUT_VOLATILE(mem, task.next_frontier, nextFChar, uint32_t, neighbor);
            last_slot = nextFChar;
            wrote_any = true;
        }
    }

    // Memory fence before completion to ensure writes complete before we continue
    addr_t cont_out = task.cont;
    if (wrote_any)
    {
        volatile uint32_t flush =
            MEM_ARR_IN_VOLATILE(mem, task.next_frontier, last_slot, uint32_t);
        if (flush == 0xFFFFFFFFu) // never true for a real vertex id; just a data dependency
            cont_out ^= (addr_t)flush;
    }

    bool sent = false;
    while (!sent)
    {
#pragma HLS PIPELINE off
        sent = argOut.write_nb(cont_out);
    }
}


void sparse_edgemap_helper(void *mem_0, void *mem_1, void *mem_2, void *mem_3, void *mem_4, void *mem_5,
                           hls::stream<sparse_edgemap_helper_args> &taskIn,
                           hls::stream<uint64_t> &argOut,
                           hls::stream<lock_req> &toLock0,
                           hls::stream<lock_resp> &fromLock0,
                           hls::stream<lock_req> &toLock1,
                           hls::stream<lock_resp> &fromLock1)
{
#pragma HLS INTERFACE ap_ctrl_none port = return

#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = argOut
#pragma HLS INTERFACE mode = axis port = toLock0
#pragma HLS INTERFACE mode = axis port = fromLock0
#pragma HLS INTERFACE mode = axis port = toLock1
#pragma HLS INTERFACE mode = axis port = fromLock1

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

    auto task = taskIn.read();

    hls::stream<uint32_t> read_vertices_out("read_vertices_out");
    hls::stream<vertex_output> output_vertices("output_vertices");
    hls::stream<uint32_t> output_neighbors("output_neighbors");
    hls::stream<uint32_t> output_unvisited_neighbors("output_unvisited_neighbors");
    hls::stream<uint32_t> output_awaiting_responses("output_awaiting_responses");
    hls::stream<uint32_t> successful_ts("successful_ts");

    // Setting deep FIFOs to mask latency and prevent lock server deadlocks
#pragma HLS STREAM variable = read_vertices_out depth = 64
#pragma HLS STREAM variable = output_vertices depth = 64
#pragma HLS STREAM variable = output_neighbors depth = 256
#pragma HLS STREAM variable = output_unvisited_neighbors depth = 256
#pragma HLS STREAM variable = output_awaiting_responses depth = 128
#pragma HLS STREAM variable = successful_ts depth = 128

#pragma HLS DATAFLOW
    read_vertices(mem_0, read_vertices_out, task);
    load_vertices(mem_1, read_vertices_out, output_vertices, task);
    read_neighbors(mem_2, output_vertices, output_neighbors, task);
    neighbor_visited_check(mem_3, output_neighbors, output_unvisited_neighbors, task);
    attempt_test_and_set(output_unvisited_neighbors, output_awaiting_responses, task, toLock0);
    recieve_test_and_set_responses(mem_4, output_awaiting_responses, successful_ts, task, fromLock0, toLock1);
    write_to_frontier(mem_5, successful_ts, task, fromLock1, argOut);
}