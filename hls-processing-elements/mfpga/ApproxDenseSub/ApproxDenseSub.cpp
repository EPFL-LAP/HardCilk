#include "ap_int.h"
#include "util.h"
#include <cstdint>
#include <stdint.h>

#define VERTICES_PER_TASK 64
#define MAX_OUTSTANDING_LOCKS 1
#define DEGREE_SUM_UNROLL 4

static const uint32_t STREAM_END = 0x80000000u;

static inline density_t compute_density_from_degree_sum(uint64_t degree_sum,
                                                        uint32_t vertex_count)
{
#pragma HLS INLINE
    if (vertex_count == 0)
        return 0;
    return ((density_t)degree_sum) / ((density_t)vertex_count * 2);
}

static inline density_t compute_threshold(density_t density, epsilon_t epsilon)
{
#pragma HLS INLINE
    return 2 * ((density_t)1 + (density_t)epsilon) * density;
}

static inline addr_t frontier_by_index(ApproxDenseSub_args &task, uint32_t index)
{
#pragma HLS INLINE
    if (index == 0)
        return task.frontier0;
    if (index == 1)
        return task.frontier1;
    return task.frontier2;
}

static inline uint32_t select_next_active(ApproxDenseSub_args &task,
                                          uint32_t current_active)
{
#pragma HLS INLINE
    bool protect_best = task.best_length != 0;
    if (current_active != 0 &&
        (!protect_best || task.frontier0 != task.best_frontier))
        return 0;
    if (current_active != 1 &&
        (!protect_best || task.frontier1 != task.best_frontier))
        return 1;
    return 2;
}

void ApproxDenseSub(void *mem_0, void *mem_1, void *mem_2, void *mem_3,
                    void *mem_4,
                    hls::stream<vertex_subset_helper_args> &taskOutGlobal,
                    hls::stream<ApproxDenseSub_args> &taskIn)
{
#pragma HLS INTERFACE ap_ctrl_none port = return
#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = taskOutGlobal
#pragma HLS INTERFACE mode = m_axi port = mem_0 bundle = gmem channel = \
    0 latency = 48 num_write_outstanding = 16 num_read_outstanding =    \
        16 max_write_burst_length = 16 max_read_burst_length =          \
            16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_1 bundle = gmem channel = \
    1 latency = 48 num_write_outstanding = 1 num_read_outstanding =     \
        64 max_read_burst_length = 16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_2 bundle = gmem channel = \
    2 latency = 48 num_write_outstanding = 1 num_read_outstanding =     \
        64 max_read_burst_length = 16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_3 bundle = gmem channel = \
    3 latency = 48 num_write_outstanding = 1 num_read_outstanding =     \
        64 max_read_burst_length = 16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_4 bundle = gmem channel = \
    4 latency = 48 num_write_outstanding = 1 num_read_outstanding =     \
        64 max_read_burst_length = 16 max_widen_bitwidth = 256

    auto task = taskIn.read();

    bool no_progress = false;
    if (task.round != 0)
    {
        uint64_t next_length = MEM_IN_VOLATILE(mem_0, task.nextFChar, uint64_t);
        MEM_OUT_VOLATILE(mem_0, task.nextFChar, uint64_t, 0);

        no_progress = next_length == task.frontier_length;
        task.frontier_length = (uint32_t)next_length;
    }
    else
    {
        MEM_OUT_VOLATILE(mem_0, task.nextFChar, uint64_t, 0);
    }

    uint32_t current_active = task.active;
    addr_t current_frontier = frontier_by_index(task, current_active);

    uint64_t degree_sum_lanes[DEGREE_SUM_UNROLL];
#pragma HLS ARRAY_PARTITION variable = degree_sum_lanes complete
    for (uint32_t lane = 0; lane < DEGREE_SUM_UNROLL; lane++)
    {
#pragma HLS UNROLL
        degree_sum_lanes[lane] = 0;
    }

    uint32_t full_groups_end = task.frontier_length & ~(DEGREE_SUM_UNROLL - 1);
    for (uint32_t i = 0; i < full_groups_end; i += DEGREE_SUM_UNROLL)
    {
#pragma HLS PIPELINE II = 1
        ap_uint<128> frontier_vertices =
            MEM_IN(mem_0, current_frontier + ((addr_t)i << 2), ap_uint<128>);
        uint32_t vertex0 = frontier_vertices.range(31, 0);
        uint32_t vertex1 = frontier_vertices.range(63, 32);
        uint32_t vertex2 = frontier_vertices.range(95, 64);
        uint32_t vertex3 = frontier_vertices.range(127, 96);

        degree_sum_lanes[0] += MEM_ARR_IN(mem_1, task.degree, vertex0, uint32_t);
        degree_sum_lanes[1] += MEM_ARR_IN(mem_2, task.degree, vertex1, uint32_t);
        degree_sum_lanes[2] += MEM_ARR_IN(mem_3, task.degree, vertex2, uint32_t);
        degree_sum_lanes[3] += MEM_ARR_IN(mem_4, task.degree, vertex3, uint32_t);
    }

    for (uint32_t i = full_groups_end; i < task.frontier_length; i++)
    {
#pragma HLS PIPELINE II = 1
        uint32_t vertex = MEM_ARR_IN(mem_0, current_frontier, i, uint32_t);
        uint32_t degree = MEM_ARR_IN(mem_1, task.degree, vertex, uint32_t);
        degree_sum_lanes[0] += degree;
    }

    uint64_t degree_sum = 0;
    for (uint32_t lane = 0; lane < DEGREE_SUM_UNROLL; lane++)
    {
#pragma HLS UNROLL
        degree_sum += degree_sum_lanes[lane];
    }

    density_t density =
        compute_density_from_degree_sum(degree_sum, task.frontier_length);
    density_t threshold = compute_threshold(density, task.epsilon);

    if (task.frontier_length == 0 || no_progress)
    {
        task.done = 1;
        task.counter = 1;
    }
    else
    {
        task.done = 0;
        if (task.best_length == 0 || density > task.best_density)
        {
            task.best_density = density;
            task.best_length = task.frontier_length;
            task.best_frontier = current_frontier;
        }

        if (density == 0)
        {
            task.done = 1;
            task.counter = 1;
        }
        else
        {
            task.counter = (task.frontier_length + VERTICES_PER_TASK - 1) / VERTICES_PER_TASK;
        }
    }

    uint32_t next_active = select_next_active(task, current_active);
    addr_t next_frontier = frontier_by_index(task, next_active);

    MEM_OUT(mem_0, task.cont + 4, uint32_t, task.source);
    MEM_OUT(mem_0, task.cont + 8, uint32_t, task.vertex_count);
    MEM_OUT(mem_0, task.cont + 12, uint32_t, task.round + 1);
    MEM_OUT(mem_0, task.cont + 16, epsilon_t, task.epsilon);
    MEM_OUT(mem_0, task.cont + 20, uint32_t, task.frontier_length);
    MEM_OUT(mem_0, task.cont + 24, uint32_t, next_active);
    MEM_OUT(mem_0, task.cont + 28, uint32_t, task.done);
    MEM_OUT(mem_0, task.cont + 32, addr_t, task.graph);
    MEM_OUT(mem_0, task.cont + 40, addr_t, task.degree);
    MEM_OUT(mem_0, task.cont + 48, addr_t, task.frontier0);
    MEM_OUT(mem_0, task.cont + 56, addr_t, task.frontier1);
    MEM_OUT(mem_0, task.cont + 64, addr_t, task.frontier2);
    MEM_OUT(mem_0, task.cont + 72, addr_t, task.nextFChar);
    MEM_OUT(mem_0, task.cont + 80, addr_t, task.cont);
    MEM_OUT(mem_0, task.cont + 88, addr_t, task.best_frontier);
    MEM_OUT(mem_0, task.cont + 96, density_t, task.best_density);
    MEM_OUT(mem_0, task.cont + 104, uint32_t, task.best_length);
    MEM_OUT(mem_0, task.cont, uint32_t, task.counter);

    if (task.done)
        return;

    for (uint32_t i = 0; i < task.frontier_length; i += VERTICES_PER_TASK)
    {
#pragma HLS PIPELINE II = 1
        vertex_subset_helper_args helper_task;
        helper_task.graph = task.graph;
        helper_task.degree = task.degree;
        helper_task.frontier = current_frontier;
        helper_task.next_frontier = next_frontier;
        helper_task.nextFChar = task.nextFChar;
        helper_task.cont = task.cont;
        helper_task.index = i;
        helper_task.round = task.round;
        helper_task.vertex_count = task.vertex_count;
        helper_task.task_vertex_count = (task.frontier_length - i) > VERTICES_PER_TASK ? VERTICES_PER_TASK : (task.frontier_length - i);
        helper_task.threshold = threshold;
        taskOutGlobal.write(helper_task);
    }
}

typedef struct
{
    addr_t neighbor_address;
    uint64_t degree;
} vertex_output;

void read_vertices(void *mem, hls::stream<uint32_t> &output_vertices,
                   vertex_subset_helper_args &task)
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

    output_vertices.write(STREAM_END);
}

void classify_vertices(void *mem, hls::stream<uint32_t> &input_vertices,
                       hls::stream<uint32_t> &to_keep,
                       hls::stream<uint32_t> &to_remove,
                       vertex_subset_helper_args &task)
{
    // Current frontier is a set, so no two iterations read/write the same D[v].
#pragma HLS DEPENDENCE variable = mem inter false
    while (true)
    {
#pragma HLS pipeline II = 1
        uint32_t vertex = input_vertices.read();
        if (vertex & STREAM_END)
        {
            break;
        }

        uint32_t degree = MEM_ARR_IN(mem, task.degree, vertex, uint32_t);
        if ((density_t)degree < task.threshold)
        {
            MEM_ARR_OUT(mem, task.degree, vertex, uint32_t, 0);
            to_remove.write(vertex);
        }
        else
        {
            to_keep.write(vertex);
        }
    }

    to_keep.write(STREAM_END);
    to_remove.write(STREAM_END);
}

void attempt_keep_write(hls::stream<uint32_t> &to_keep,
                        hls::stream<uint32_t> &keep_awaiting_response,
                        vertex_subset_helper_args &task,
                        hls::stream<lock_req> &toLock)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        uint32_t vertex = to_keep.read();
        if (vertex & STREAM_END)
        {
            break;
        }

        lock_req req = make_lock_req(task.nextFChar, 1, LOCK_OP_ADD_N_RETURN_CURRENT,
                                     true, ATOMIC_MODE_DOUBLEWORD);
        toLock.write(req);
        keep_awaiting_response.write(vertex);
    }

    keep_awaiting_response.write(STREAM_END);
}

void write_kept_vertices(void *mem, hls::stream<uint32_t> &keep_awaiting_response,
                         hls::stream<uint8_t> &keep_done,
                         vertex_subset_helper_args &task,
                         hls::stream<lock_resp> &fromLock)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        uint32_t vertex = keep_awaiting_response.read();
        if (vertex & STREAM_END)
        {
            break;
        }

        lock_resp resp = fromLock.read();
        if (lock_resp_success(resp))
        {
            uint32_t nextFChar = lock_resp_current(resp);
            MEM_ARR_OUT(mem, task.next_frontier, nextFChar, uint32_t, vertex);
        }
    }

    keep_done.write(1);
}

void load_removed_vertices(void *mem, hls::stream<uint32_t> &to_remove,
                           hls::stream<vertex_output> &output_vertices,
                           vertex_subset_helper_args &task)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        uint32_t vertex = to_remove.read();
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

void read_removed_neighbors(void *mem, hls::stream<vertex_output> &input_vertices,
                            hls::stream<uint32_t> &output_neighbors,
                            vertex_subset_helper_args &task)
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

void attempt_degree_decrement(hls::stream<uint32_t> &input_neighbors,
                              hls::stream<uint32_t> &decrement_awaiting_response,
                              vertex_subset_helper_args &task,
                              hls::stream<lock_req> &toLock)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        uint32_t neighbor = input_neighbors.read();
        if (neighbor & STREAM_END)
        {
            break;
        }

        lock_req req = make_lock_req(task.degree + ((addr_t)neighbor << 2),
                                     (ap_uint<64>)0xFFFFFFFFu,
                                     LOCK_OP_ADD_N_RETURN_CURRENT, true,
                                     ATOMIC_MODE_WORD);
        toLock.write(req);
        decrement_awaiting_response.write(neighbor);
    }

    decrement_awaiting_response.write(STREAM_END);
}

void drain_degree_decrement_responses(hls::stream<uint32_t> &decrement_awaiting_response,
                                      hls::stream<uint8_t> &decrement_done,
                                      hls::stream<lock_resp> &fromLock)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        uint32_t token = decrement_awaiting_response.read();
        if (token & STREAM_END)
        {
            break;
        }

        lock_resp resp = fromLock.read();
        (void)resp;
    }

    decrement_done.write(1);
}

void finish_helper(hls::stream<uint8_t> &keep_done,
                   hls::stream<uint8_t> &decrement_done,
                   hls::stream<uint64_t> &argOut,
                   vertex_subset_helper_args &task)
{
    keep_done.read();
    decrement_done.read();

    bool sent = false;
    while (!sent)
    {
#pragma HLS PIPELINE off
        sent = argOut.write_nb(task.cont);
    }
}

// ---------------------------------------------------------
// Top Level Vertex Subset Helper
// ---------------------------------------------------------

void vertex_subset_helper(void *mem_0, void *mem_1, void *mem_2, void *mem_3,
                          void *mem_4, void *mem_5,
                          hls::stream<vertex_subset_helper_args> &taskIn,
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
    1 latency = 48 num_write_outstanding = 16 num_read_outstanding =    \
        16 max_write_burst_length = 16 max_read_burst_length =          \
            16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_2 bundle = gmem channel = \
    2 latency = 48 num_write_outstanding = 16 num_read_outstanding =    \
        16 max_write_burst_length = 16 max_read_burst_length =          \
            16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_3 bundle = gmem channel = \
    3 latency = 48 num_write_outstanding = 1 num_read_outstanding =     \
        64 max_read_burst_length = 16 max_widen_bitwidth = 256
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
    hls::stream<uint32_t> to_keep("to_keep");
    hls::stream<uint32_t> to_remove("to_remove");
    hls::stream<uint32_t> keep_awaiting_response("keep_awaiting_response");
    hls::stream<vertex_output> removed_vertices("removed_vertices");
    hls::stream<uint32_t> removed_neighbors("removed_neighbors");
    hls::stream<uint32_t> decrement_awaiting_response("decrement_awaiting_response");
    hls::stream<uint8_t> keep_done("keep_done");
    hls::stream<uint8_t> decrement_done("decrement_done");

#pragma HLS STREAM variable = read_vertices_out depth = 64
#pragma HLS STREAM variable = to_keep depth = 64
#pragma HLS STREAM variable = to_remove depth = 64
#pragma HLS STREAM variable = keep_awaiting_response depth = 128
#pragma HLS STREAM variable = removed_vertices depth = 64
#pragma HLS STREAM variable = removed_neighbors depth = 256
#pragma HLS STREAM variable = decrement_awaiting_response depth = 128
#pragma HLS STREAM variable = keep_done depth = 2
#pragma HLS STREAM variable = decrement_done depth = 2

#pragma HLS DATAFLOW
    read_vertices(mem_0, read_vertices_out, task);
    classify_vertices(mem_1, read_vertices_out, to_keep, to_remove, task);
    attempt_keep_write(to_keep, keep_awaiting_response, task, toLock0);
    write_kept_vertices(mem_2, keep_awaiting_response, keep_done, task, fromLock0);
    load_removed_vertices(mem_3, to_remove, removed_vertices, task);
    read_removed_neighbors(mem_4, removed_vertices, removed_neighbors, task);
    attempt_degree_decrement(removed_neighbors, decrement_awaiting_response, task,
                             toLock1);
    drain_degree_decrement_responses(decrement_awaiting_response, decrement_done,
                                     fromLock1);
    finish_helper(keep_done, decrement_done, argOut, task);
}
