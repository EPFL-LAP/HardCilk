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
    return ((density_t)degree_sum) / ((density_t)vertex_count);
}

static inline density_t compute_threshold(density_t density, epsilon_t epsilon)
{
#pragma HLS INLINE
    return ((density_t)1 + (density_t)epsilon) * density;
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

    // Per-phase continuation state filled in by the branches below.
    uint32_t next_phase = PHASE_CLASSIFY;
    uint32_t next_round = task.round;
    uint32_t next_active = task.active;
    uint32_t next_frontier_length = task.frontier_length;
    uint32_t next_counter = 1;
    uint32_t done_flag = 0;

    // Classify-wave spawn state.
    bool spawn_classify = false;
    addr_t classify_frontier = 0;
    addr_t classify_next_frontier = 0;
    uint32_t classify_length = 0;
    density_t classify_threshold = 0;

    // Decrement-wave spawn state.
    bool spawn_decrement = false;
    uint64_t removed_count = 0;

    if (task.phase == PHASE_CLASSIFY)
    {
        uint32_t current_active = task.active;
        addr_t current_frontier = frontier_by_index(task, current_active);

        uint64_t degree_sum_lanes[DEGREE_SUM_UNROLL];
#pragma HLS ARRAY_PARTITION variable = degree_sum_lanes complete
        for (uint32_t lane = 0; lane < DEGREE_SUM_UNROLL; lane++)
        {
#pragma HLS UNROLL
            degree_sum_lanes[lane] = 0;
        }

        uint32_t full_groups_end =
            task.frontier_length & ~(DEGREE_SUM_UNROLL - 1);
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

        // The reference records the best subgraph at the top of every round,
        // before the density==0 / empty-R termination checks.
        if (task.frontier_length != 0 &&
            (task.best_length == 0 || density > task.best_density))
        {
            task.best_density = density;
            task.best_length = task.frontier_length;
            task.best_frontier = current_frontier;
        }

        if (task.frontier_length == 0 || density == 0)
        {
            done_flag = 1;
            next_counter = 1;
            next_phase = PHASE_CLASSIFY;
            next_round = task.round;
            next_active = current_active;
            next_frontier_length = task.frontier_length;
        }
        else
        {
            // Reset the survivor / removed atomic counters for this wave.
            MEM_OUT_VOLATILE(mem_0, task.nextFChar, uint64_t, 0);
            MEM_OUT_VOLATILE(mem_0, task.removedChar, uint64_t, 0);

            uint32_t na = select_next_active(task, current_active);

            done_flag = 0;
            next_phase = PHASE_DECREMENT;
            next_round = task.round;
            next_active = na; // survivors land here; carried into round+1
            next_frontier_length = task.frontier_length;
            next_counter =
                (task.frontier_length + VERTICES_PER_TASK - 1) / VERTICES_PER_TASK;

            spawn_classify = true;
            classify_frontier = current_frontier;
            classify_next_frontier = frontier_by_index(task, na);
            classify_length = task.frontier_length;
            classify_threshold = threshold;
        }
    }
    else // PHASE_DECREMENT
    {
        removed_count = MEM_IN_VOLATILE(mem_0, task.removedChar, uint64_t);
        uint64_t survivor_count = MEM_IN_VOLATILE(mem_0, task.nextFChar, uint64_t);

        // No vertices fell below threshold this round (reference: R empty) -> stop.
        if (removed_count == 0)
        {
            done_flag = 1;
            next_counter = 1;
            next_phase = PHASE_CLASSIFY;
            next_round = task.round + 1;
            next_active = task.active;
            next_frontier_length = (uint32_t)survivor_count;
        }
        else
        {
            done_flag = 0;
            next_phase = PHASE_CLASSIFY;
            next_round = task.round + 1;
            next_active = task.active; // survivor buffer chosen in classify wave
            next_frontier_length = (uint32_t)survivor_count;
            next_counter =
                (uint32_t)((removed_count + VERTICES_PER_TASK - 1) / VERTICES_PER_TASK);

            spawn_decrement = true;
        }
    }

    // Continuation writeback. removed_list (cont+112) and removedChar (cont+120)
    // are constant for the whole run, set once by the host; they are never
    // rewritten here and persist across continuations.
    MEM_OUT(mem_0, task.cont + 4, uint32_t, task.source);
    MEM_OUT(mem_0, task.cont + 8, uint32_t, task.vertex_count);
    MEM_OUT(mem_0, task.cont + 12, uint32_t, next_round);
    MEM_OUT(mem_0, task.cont + 16, epsilon_t, task.epsilon);
    MEM_OUT(mem_0, task.cont + 20, uint32_t, next_frontier_length);
    MEM_OUT(mem_0, task.cont + 24, uint32_t, next_active);
    MEM_OUT(mem_0, task.cont + 28, uint32_t, done_flag);
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
    MEM_OUT(mem_0, task.cont + 108, uint32_t, next_phase);
    MEM_OUT(mem_0, task.cont, uint32_t, next_counter);

    if (done_flag)
        return;

    if (spawn_classify)
    {
        for (uint32_t i = 0; i < classify_length; i += VERTICES_PER_TASK)
        {
#pragma HLS PIPELINE II = 1
            vertex_subset_helper_args helper_task;
            helper_task.phase = PHASE_CLASSIFY;
            helper_task.graph = task.graph;
            helper_task.degree = task.degree;
            helper_task.frontier = classify_frontier;
            helper_task.next_frontier = classify_next_frontier;
            helper_task.nextFChar = task.nextFChar;
            helper_task.removed_list = task.removed_list;
            helper_task.removedChar = task.removedChar;
            helper_task.cont = task.cont;
            helper_task.index = i;
            helper_task.round = task.round;
            helper_task.vertex_count = task.vertex_count;
            helper_task.task_vertex_count =
                (classify_length - i) > VERTICES_PER_TASK
                    ? VERTICES_PER_TASK
                    : (classify_length - i);
            helper_task.threshold = classify_threshold;
            taskOutGlobal.write(helper_task);
        }
    }

    if (spawn_decrement)
    {
        for (uint32_t i = 0; i < (uint32_t)removed_count; i += VERTICES_PER_TASK)
        {
#pragma HLS PIPELINE II = 1
            vertex_subset_helper_args helper_task;
            helper_task.phase = PHASE_DECREMENT;
            helper_task.graph = task.graph;
            helper_task.degree = task.degree;
            helper_task.removed_list = task.removed_list;
            helper_task.cont = task.cont;
            helper_task.index = i;
            helper_task.round = task.round;
            helper_task.vertex_count = task.vertex_count;
            helper_task.task_vertex_count =
                ((uint32_t)removed_count - i) > VERTICES_PER_TASK
                    ? VERTICES_PER_TASK
                    : ((uint32_t)removed_count - i);
            taskOutGlobal.write(helper_task);
        }
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

void attempt_removed_write(hls::stream<uint32_t> &to_remove,
                           hls::stream<uint32_t> &removed_awaiting_response,
                           vertex_subset_helper_args &task,
                           hls::stream<lock_req> &toLock)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        uint32_t vertex = to_remove.read();
        if (vertex & STREAM_END)
        {
            break;
        }

        lock_req req = make_lock_req(task.removedChar, 1, LOCK_OP_ADD_N_RETURN_CURRENT,
                                     true, ATOMIC_MODE_DOUBLEWORD);
        toLock.write(req);
        removed_awaiting_response.write(vertex);
    }

    removed_awaiting_response.write(STREAM_END);
}

void write_removed_vertices(void *mem,
                            hls::stream<uint32_t> &removed_awaiting_response,
                            hls::stream<uint8_t> &removed_done,
                            vertex_subset_helper_args &task,
                            hls::stream<lock_resp> &fromLock)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        uint32_t vertex = removed_awaiting_response.read();
        if (vertex & STREAM_END)
        {
            break;
        }

        lock_resp resp = fromLock.read();
        if (lock_resp_success(resp))
        {
            uint32_t slot = lock_resp_current(resp);
            MEM_ARR_OUT(mem, task.removed_list, slot, uint32_t, vertex);
        }
    }

    removed_done.write(1);
}

void finish_classify(hls::stream<uint8_t> &keep_done,
                     hls::stream<uint8_t> &removed_done,
                     hls::stream<uint64_t> &argOut,
                     vertex_subset_helper_args &task)
{
    keep_done.read();
    removed_done.read();

    bool sent = false;
    while (!sent)
    {
#pragma HLS PIPELINE off
        sent = argOut.write_nb(task.cont);
    }
}

void classify_wave(void *mem_frontier, void *mem_degree, void *mem_keep,
                   void *mem_removed, vertex_subset_helper_args &task,
                   hls::stream<uint64_t> &argOut,
                   hls::stream<lock_req> &toLockKeep,
                   hls::stream<lock_resp> &fromLockKeep,
                   hls::stream<lock_req> &toLockRemoved,
                   hls::stream<lock_resp> &fromLockRemoved)
{
    hls::stream<uint32_t> read_vertices_out("read_vertices_out");
    hls::stream<uint32_t> to_keep("to_keep");
    hls::stream<uint32_t> to_remove("to_remove");
    hls::stream<uint32_t> keep_awaiting_response("keep_awaiting_response");
    hls::stream<uint32_t> removed_awaiting_response("removed_awaiting_response");
    hls::stream<uint8_t> keep_done("keep_done");
    hls::stream<uint8_t> removed_done("removed_done");

#pragma HLS STREAM variable = read_vertices_out depth = 64
#pragma HLS STREAM variable = to_keep depth = 64
#pragma HLS STREAM variable = to_remove depth = 64
#pragma HLS STREAM variable = keep_awaiting_response depth = 128
#pragma HLS STREAM variable = removed_awaiting_response depth = 128
#pragma HLS STREAM variable = keep_done depth = 2
#pragma HLS STREAM variable = removed_done depth = 2

#pragma HLS DATAFLOW
    read_vertices(mem_frontier, read_vertices_out, task);
    classify_vertices(mem_degree, read_vertices_out, to_keep, to_remove, task);
    attempt_keep_write(to_keep, keep_awaiting_response, task, toLockKeep);
    write_kept_vertices(mem_keep, keep_awaiting_response, keep_done, task,
                        fromLockKeep);
    attempt_removed_write(to_remove, removed_awaiting_response, task,
                          toLockRemoved);
    write_removed_vertices(mem_removed, removed_awaiting_response, removed_done,
                           task, fromLockRemoved);
    finish_classify(keep_done, removed_done, argOut, task);
}


void read_removed_vertices(void *mem, hls::stream<uint32_t> &to_remove,
                           vertex_subset_helper_args &task)
{
    for (uint32_t i = 0; i < VERTICES_PER_TASK; i++)
    {
#pragma HLS pipeline II = 1
        uint32_t vertex =
            MEM_ARR_IN(mem, task.removed_list, task.index + i, uint32_t);
        if (i < task.task_vertex_count)
        {
            to_remove.write(vertex);
        }
    }

    to_remove.write(STREAM_END);
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

void finish_decrement(hls::stream<uint8_t> &decrement_done,
                      hls::stream<uint64_t> &argOut,
                      vertex_subset_helper_args &task)
{
    decrement_done.read();

    bool sent = false;
    while (!sent)
    {
#pragma HLS PIPELINE off
        sent = argOut.write_nb(task.cont);
    }
}

void decrement_wave(void *mem_removed, void *mem_graph, void *mem_neighbors,
                    vertex_subset_helper_args &task,
                    hls::stream<uint64_t> &argOut,
                    hls::stream<lock_req> &toLock,
                    hls::stream<lock_resp> &fromLock)
{
    hls::stream<uint32_t> to_remove("to_remove");
    hls::stream<vertex_output> removed_vertices("removed_vertices");
    hls::stream<uint32_t> removed_neighbors("removed_neighbors");
    hls::stream<uint32_t> decrement_awaiting_response("decrement_awaiting_response");
    hls::stream<uint8_t> decrement_done("decrement_done");

#pragma HLS STREAM variable = to_remove depth = 64
#pragma HLS STREAM variable = removed_vertices depth = 64
#pragma HLS STREAM variable = removed_neighbors depth = 256
#pragma HLS STREAM variable = decrement_awaiting_response depth = 128
#pragma HLS STREAM variable = decrement_done depth = 2

#pragma HLS DATAFLOW
    read_removed_vertices(mem_removed, to_remove, task);
    load_removed_vertices(mem_graph, to_remove, removed_vertices, task);
    read_removed_neighbors(mem_neighbors, removed_vertices, removed_neighbors, task);
    attempt_degree_decrement(removed_neighbors, decrement_awaiting_response, task,
                             toLock);
    drain_degree_decrement_responses(decrement_awaiting_response, decrement_done,
                                     fromLock);
    finish_decrement(decrement_done, argOut, task);
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
    4 latency = 48 num_write_outstanding = 1 num_read_outstanding =     \
        64 max_read_burst_length = 16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_5 bundle = gmem channel = \
    5 latency = 48 num_write_outstanding = 1 num_read_outstanding =     \
        64 max_read_burst_length = 16 max_widen_bitwidth = 256

    auto task = taskIn.read();

    if (task.phase == PHASE_CLASSIFY)
    {
        // Survivor counter on lock0, removed counter on lock1.
        classify_wave(mem_0, mem_1, mem_2, mem_3, task, argOut, toLock0, fromLock0,
                      toLock1, fromLock1);
    }
    else
    {
        // Induced-degree decrements on lock1.
        decrement_wave(mem_0, mem_4, mem_5, task, argOut, toLock1, fromLock1);
    }
}
