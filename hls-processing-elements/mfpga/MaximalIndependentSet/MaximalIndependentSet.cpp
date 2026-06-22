#include "ap_int.h"
#include "util.h"
#include <cstdint>
#include <stdint.h>

#define VERTICES_PER_TASK 64

static const uint32_t STREAM_END = 0x80000000u;

typedef struct
{
    uint32_t vertex;
    uint32_t neighbor;
    uint32_t vertex_priority;
    bool last;
} neighbor_item;

typedef struct
{
    uint32_t vertex;
    uint32_t count;
} count_item;

typedef struct
{
    uint32_t vertex;
    bool higher_priority;
    bool last;
} priority_mark;

void read_vertices(hls::stream<uint32_t> &output_vertices, NGS_args &task)
{
    for (uint32_t i = 0; i < VERTICES_PER_TASK; i++)
    {
#pragma HLS pipeline II = 1
        uint32_t vertex = task.index + i;
        if (i < task.task_vertex_count && vertex < task.vertex_count)
        {
            output_vertices.write(vertex);
        }
    }

    output_vertices.write(STREAM_END);
}

void read_neighbors(void *mem_graph, void *mem_priority,
                    hls::stream<uint32_t> &input_vertices,
                    hls::stream<neighbor_item> &output_neighbors,
                    NGS_args &task)
{
    while (true)
    {
        uint32_t vertex = input_vertices.read();
        if (vertex & STREAM_END)
        {
            break;
        }

        ap_uint<128> bulk =
            MEM_IN(mem_graph, task.graph + ((addr_t)vertex << 4), ap_uint<128>);
        addr_t neighbor_address = bulk.range(63, 0);
        uint64_t degree = bulk.range(127, 64);
        uint32_t vertex_priority =
            MEM_ARR_IN(mem_priority, task.priority, vertex, uint32_t);

        for (uint32_t n = 0; n < degree; n++)
        {
#pragma HLS pipeline II = 1
            neighbor_item neighbor;
            neighbor.vertex = vertex;
            neighbor.neighbor = MEM_ARR_IN(mem_graph, neighbor_address, n, uint32_t);
            neighbor.vertex_priority = vertex_priority;
            neighbor.last = false;
            output_neighbors.write(neighbor);
        }

        neighbor_item done;
        done.vertex = vertex;
        done.neighbor = 0;
        done.vertex_priority = vertex_priority;
        done.last = true;
        output_neighbors.write(done);
    }

    neighbor_item sentinel;
    sentinel.vertex = STREAM_END;
    sentinel.neighbor = 0;
    sentinel.vertex_priority = 0;
    sentinel.last = true;
    output_neighbors.write(sentinel);
}

void check_higher_priority_neighbors(void *mem_priority,
                                     hls::stream<neighbor_item> &input_neighbors,
                                     hls::stream<priority_mark> &output_marks,
                                     NGS_args &task)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        neighbor_item item = input_neighbors.read();
        if (item.vertex & STREAM_END)
        {
            break;
        }

        priority_mark mark;
        mark.vertex = item.vertex;
        mark.last = item.last;
        if (!item.last)
        {
            uint32_t neighbor_priority =
                MEM_ARR_IN(mem_priority, task.priority, item.neighbor, uint32_t);
            mark.higher_priority = neighbor_priority > item.vertex_priority;
        }
        else
        {
            mark.higher_priority = false;
        }
        output_marks.write(mark);
    }

    priority_mark sentinel;
    sentinel.vertex = STREAM_END;
    sentinel.higher_priority = false;
    sentinel.last = true;
    output_marks.write(sentinel);
}

void accumulate_higher_priority_counts(hls::stream<priority_mark> &input_marks,
                                       hls::stream<count_item> &output_counts,
                                       NGS_args &task)
{
    uint32_t count = 0;

    while (true)
    {
#pragma HLS pipeline II = 1
        priority_mark mark = input_marks.read();
        if (mark.vertex & STREAM_END)
        {
            break;
        }

        if (!mark.last)
        {
            if (mark.higher_priority)
                count++;
        }
        else
        {
            count_item out;
            out.vertex = mark.vertex;
            out.count = count;
            output_counts.write(out);
            count = 0;
        }
    }

    count_item sentinel;
    sentinel.vertex = STREAM_END;
    sentinel.count = 0;
    output_counts.write(sentinel);
}

void write_counts(void *mem, hls::stream<count_item> &input_counts,
                  hls::stream<uint64_t> &argOut, NGS_args &task)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        count_item item = input_counts.read();
        if (item.vertex & STREAM_END)
        {
            break;
        }

        MEM_ARR_OUT(mem, task.nghCount, item.vertex, uint32_t, item.count);
    }

    bool sent = false;
    while (!sent)
    {
#pragma HLS PIPELINE off
        sent = argOut.write_nb(task.cont);
    }
}

// ---------------------------------------------------------
// Top Level CountNGH / NGS Primitive
// ---------------------------------------------------------

void NGS(void *mem_0, void *mem_1, void *mem_2, void *mem_3,
         hls::stream<NGS_args> &taskIn,
         hls::stream<uint64_t> &argOut)
{
#pragma HLS INTERFACE ap_ctrl_none port = return

#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = argOut

#pragma HLS INTERFACE mode = m_axi port = mem_0 bundle = gmem channel = \
    0 latency = 48 num_write_outstanding = 1 num_read_outstanding =     \
        64 max_read_burst_length = 16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_1 bundle = gmem channel = \
    1 latency = 48 num_write_outstanding = 1 num_read_outstanding =     \
        64 max_read_burst_length = 16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_2 bundle = gmem channel = \
    2 latency = 48 num_write_outstanding = 1 num_read_outstanding =     \
        64 max_read_burst_length = 16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_3 bundle = gmem channel = \
    3 latency = 48 num_write_outstanding = 16 num_read_outstanding =    \
        16 max_write_burst_length = 16 max_read_burst_length =          \
            16 max_widen_bitwidth = 256

    auto task = taskIn.read();

    hls::stream<uint32_t> vertices("vertices");
    hls::stream<neighbor_item> neighbors("neighbors");
    hls::stream<priority_mark> marks("marks");
    hls::stream<count_item> counts("counts");

#pragma HLS STREAM variable = vertices depth = 64
#pragma HLS STREAM variable = neighbors depth = 256
#pragma HLS STREAM variable = marks depth = 256
#pragma HLS STREAM variable = counts depth = 64

#pragma HLS DATAFLOW
    read_vertices(vertices, task);
    read_neighbors(mem_0, mem_1, vertices, neighbors, task);
    check_higher_priority_neighbors(mem_2, neighbors, marks, task);
    accumulate_higher_priority_counts(marks, counts, task);
    write_counts(mem_3, counts, argOut, task);
}

static inline addr_t mis_covered_buffer(MIS_args &task, uint32_t active)
{
#pragma HLS INLINE
    return active == 0 ? task.covered0 : task.covered1;
}

void MaximalIndependentSet(void *mem_0,
                           hls::stream<NGS_args> &taskOutGlobal,
                           hls::stream<mis_loop_helper_args> &taskOutGlobal_1,
                           hls::stream<MIS_args> &taskIn)
{
#pragma HLS INTERFACE ap_ctrl_none port = return
#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = taskOutGlobal
#pragma HLS INTERFACE mode = axis port = taskOutGlobal_1
#pragma HLS INTERFACE mode = m_axi port = mem_0 bundle = gmem channel = \
    0 latency = 48 num_write_outstanding = 16 num_read_outstanding =    \
        16 max_write_burst_length = 16 max_read_burst_length =          \
            16 max_widen_bitwidth = 256

    auto task = taskIn.read();
    uint32_t chunk_count =
        (task.vertex_count + VERTICES_PER_TASK - 1) / VERTICES_PER_TASK;
    bool launch_ngs = task.ngs_done == 0;

    if (launch_ngs)
    {
        MEM_OUT_VOLATILE(mem_0, task.nextFChar, uint64_t, 0);
        task.last_covered_length = 0;
        task.num_finished = 0;
        task.ngs_done = 1;
        task.loop_started = 0;
        task.done = (task.vertex_count == 0) ? 1 : 0;
        task.counter = task.done ? 1 : chunk_count;
    }
    else
    {
        bool had_loop_pass = task.loop_started != 0;
        if (had_loop_pass)
        {
            uint64_t covered_length = MEM_IN_VOLATILE(mem_0, task.nextFChar, uint64_t);
            MEM_OUT_VOLATILE(mem_0, task.nextFChar, uint64_t, 0);
            task.last_covered_length = (uint32_t)covered_length;
            task.num_finished += (uint32_t)covered_length;
            task.active = 1 - task.active;
        }
        else
        {
            MEM_OUT_VOLATILE(mem_0, task.nextFChar, uint64_t, 0);
            task.last_covered_length = 0;
            task.loop_started = 1;
        }

        bool no_progress = had_loop_pass && task.last_covered_length == 0;
        if (task.num_finished >= task.vertex_count || no_progress)
        {
            task.done = 1;
            task.counter = 1;
        }
        else
        {
            task.done = 0;
            task.counter = chunk_count;
        }
    }

    // Need to do 1 at a time so that the counter gets written last.
    MEM_OUT(mem_0, task.cont + 4, uint32_t, task.vertex_count);
    MEM_OUT(mem_0, task.cont + 8, uint32_t, task.ngs_done);
    MEM_OUT(mem_0, task.cont + 12, uint32_t, task.active);
    MEM_OUT(mem_0, task.cont + 16, uint32_t, task.done);
    MEM_OUT(mem_0, task.cont + 20, uint32_t, task.num_finished);
    MEM_OUT(mem_0, task.cont + 24, uint32_t, task.last_covered_length);
    MEM_OUT(mem_0, task.cont + 28, uint32_t, task.loop_started);
    MEM_OUT(mem_0, task.cont + 32, addr_t, task.graph);
    MEM_OUT(mem_0, task.cont + 40, addr_t, task.priority);
    MEM_OUT(mem_0, task.cont + 48, addr_t, task.nghCount);
    MEM_OUT(mem_0, task.cont + 56, addr_t, task.covered);
    MEM_OUT(mem_0, task.cont + 64, addr_t, task.inMis);
    MEM_OUT(mem_0, task.cont + 72, addr_t, task.covered0);
    MEM_OUT(mem_0, task.cont + 80, addr_t, task.covered1);
    MEM_OUT(mem_0, task.cont + 88, addr_t, task.nextFChar);
    MEM_OUT(mem_0, task.cont + 96, addr_t, task.cont);
    // Otherwise the counter would get written first.
    MEM_OUT(mem_0, task.cont, uint32_t, task.counter);

    if (task.done)
        return;

    if (launch_ngs)
    {
        for (uint32_t i = 0; i < task.vertex_count; i += VERTICES_PER_TASK)
        {
#pragma HLS PIPELINE II = 1
            NGS_args ngs_task;
            ngs_task.graph = task.graph;
            ngs_task.priority = task.priority;
            ngs_task.nghCount = task.nghCount;
            ngs_task.cont = task.cont;
            ngs_task.index = i;
            ngs_task.vertex_count = task.vertex_count;
            ngs_task.task_vertex_count =
                (task.vertex_count - i) > VERTICES_PER_TASK ? VERTICES_PER_TASK : (task.vertex_count - i);
            ngs_task.counter = 0;
            taskOutGlobal.write(ngs_task);
        }
        return;
    }

    addr_t next_covered = mis_covered_buffer(task, task.active);
    for (uint32_t i = 0; i < task.vertex_count; i += VERTICES_PER_TASK)
    {
#pragma HLS PIPELINE II = 1
        mis_loop_helper_args helper_task;
        helper_task.graph = task.graph;
        helper_task.priority = task.priority;
        helper_task.nghCount = task.nghCount;
        helper_task.covered = task.covered;
        helper_task.inMis = task.inMis;
        helper_task.next_covered = next_covered;
        helper_task.nextFChar = task.nextFChar;
        helper_task.cont = task.cont;
        helper_task.index = i;
        helper_task.vertex_count = task.vertex_count;
        helper_task.task_vertex_count =
            (task.vertex_count - i) > VERTICES_PER_TASK ? VERTICES_PER_TASK : (task.vertex_count - i);
        taskOutGlobal_1.write(helper_task);
    }
}

void mis_read_vertices(hls::stream<uint32_t> &output_vertices,
                       mis_loop_helper_args &task)
{
    for (uint32_t i = 0; i < VERTICES_PER_TASK; i++)
    {
#pragma HLS pipeline II = 1
        uint32_t vertex = task.index + i;
        if (i < task.task_vertex_count && vertex < task.vertex_count)
        {
            output_vertices.write(vertex);
        }
    }

    output_vertices.write(STREAM_END);
}

void select_uncovered_zero_vertices(void *mem_covered, void *mem_count,
                                    hls::stream<uint32_t> &input_vertices,
                                    hls::stream<uint32_t> &selected_vertices,
                                    mis_loop_helper_args &task)
{
    // Vertex stream is unique within the helper chunk, so per-vertex state has
    // no loop-carried dependence here.
#pragma HLS DEPENDENCE variable = mem_covered inter false
#pragma HLS DEPENDENCE variable = mem_count inter false
    while (true)
    {
#pragma HLS pipeline II = 1
        uint32_t vertex = input_vertices.read();
        if (vertex & STREAM_END)
        {
            break;
        }

        uint8_t covered = MEM_ARR_IN(mem_covered, task.covered, vertex, uint8_t);
        uint32_t count = MEM_ARR_IN(mem_count, task.nghCount, vertex, uint32_t);
        if (covered == 0 && count == 0)
        {
            MEM_ARR_OUT(mem_covered, task.inMis, vertex, uint8_t, 1);
            selected_vertices.write(vertex);
        }
    }

    selected_vertices.write(STREAM_END);
}

void expand_selected_vertices(void *mem, hls::stream<uint32_t> &selected_vertices,
                              hls::stream<uint32_t> &cover_candidates,
                              mis_loop_helper_args &task)
{
    while (true)
    {
        uint32_t selected = selected_vertices.read();
        if (selected & STREAM_END)
        {
            break;
        }

        cover_candidates.write(selected);

        ap_uint<128> bulk =
            MEM_IN(mem, task.graph + ((addr_t)selected << 4), ap_uint<128>);
        addr_t neighbor_address = bulk.range(63, 0);
        uint64_t degree = bulk.range(127, 64);

        for (uint32_t n = 0; n < degree; n++)
        {
#pragma HLS pipeline II = 1
            uint32_t candidate = MEM_ARR_IN(mem, neighbor_address, n, uint32_t);
            cover_candidates.write(candidate);
        }
    }

    cover_candidates.write(STREAM_END);
}

void attempt_cover_vertices(hls::stream<uint32_t> &cover_candidates,
                            hls::stream<uint32_t> &cover_awaiting_response,
                            mis_loop_helper_args &task,
                            hls::stream<lock_req> &toLock)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        uint32_t vertex = cover_candidates.read();
        if (vertex & STREAM_END)
        {
            break;
        }

        lock_req req = make_lock_req(task.covered + (addr_t)vertex, 1,
                                     LOCK_OP_SET_AND_RETURN_CURRENT, false,
                                     ATOMIC_MODE_BYTE);
        toLock.write(req);
        cover_awaiting_response.write(vertex);
    }

    cover_awaiting_response.write(STREAM_END);
}

void receive_cover_responses(hls::stream<uint32_t> &cover_awaiting_response,
                             hls::stream<uint32_t> &newly_covered_for_buffer,
                             hls::stream<uint32_t> &newly_covered_for_update,
                             mis_loop_helper_args &task,
                             hls::stream<lock_resp> &fromLock,
                             hls::stream<lock_req> &toLock)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        uint32_t vertex = cover_awaiting_response.read();
        if (vertex & STREAM_END)
        {
            break;
        }

        lock_resp resp = fromLock.read();
        if (lock_resp_success(resp) && lock_resp_current_byte(resp) == 0)
        {
            newly_covered_for_buffer.write(vertex);
            newly_covered_for_update.write(vertex);
            lock_req req = make_lock_req(task.nextFChar, 1,
                                         LOCK_OP_ADD_N_RETURN_CURRENT, true,
                                         ATOMIC_MODE_DOUBLEWORD);
            toLock.write(req);
        }
    }

    newly_covered_for_buffer.write(STREAM_END);
    newly_covered_for_update.write(STREAM_END);
}

void write_newly_covered(void *mem, hls::stream<uint32_t> &newly_covered,
                         hls::stream<uint8_t> &buffer_done,
                         mis_loop_helper_args &task,
                         hls::stream<lock_resp> &fromLock)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        uint32_t vertex = newly_covered.read();
        if (vertex & STREAM_END)
        {
            break;
        }

        lock_resp resp = fromLock.read();
        if (lock_resp_success(resp))
        {
            uint32_t slot = lock_resp_current(resp);
            MEM_ARR_OUT(mem, task.next_covered, slot, uint32_t, vertex);
        }
    }

    buffer_done.write(1);
}

void read_newly_covered_neighbors(void *mem, hls::stream<uint32_t> &newly_covered,
                                  hls::stream<neighbor_item> &neighbors_to_update,
                                  mis_loop_helper_args &task)
{
    while (true)
    {
        uint32_t vertex = newly_covered.read();
        if (vertex & STREAM_END)
        {
            break;
        }

        uint32_t covered_priority =
            MEM_ARR_IN(mem, task.priority, vertex, uint32_t);
        ap_uint<128> bulk =
            MEM_IN(mem, task.graph + ((addr_t)vertex << 4), ap_uint<128>);
        addr_t neighbor_address = bulk.range(63, 0);
        uint64_t degree = bulk.range(127, 64);

        for (uint32_t n = 0; n < degree; n++)
        {
#pragma HLS pipeline II = 1
            neighbor_item neighbor;
            neighbor.vertex = vertex;
            neighbor.neighbor = MEM_ARR_IN(mem, neighbor_address, n, uint32_t);
            neighbor.vertex_priority = covered_priority;
            neighbor.last = false;
            neighbors_to_update.write(neighbor);
        }
    }

    neighbor_item sentinel;
    sentinel.vertex = STREAM_END;
    sentinel.neighbor = 0;
    sentinel.vertex_priority = 0;
    sentinel.last = true;
    neighbors_to_update.write(sentinel);
}

void attempt_priority_updates(void *mem_covered, void *mem_priority,
                              hls::stream<neighbor_item> &neighbors_to_update,
                              hls::stream<uint32_t> &update_awaiting_response,
                              mis_loop_helper_args &task,
                              hls::stream<lock_req> &toLock)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        neighbor_item item = neighbors_to_update.read();
        if (item.vertex & STREAM_END)
        {
            break;
        }

        uint8_t covered =
            MEM_ARR_IN(mem_covered, task.covered, item.neighbor, uint8_t);
        uint32_t neighbor_priority =
            MEM_ARR_IN(mem_priority, task.priority, item.neighbor, uint32_t);
        if (covered == 0 && item.vertex_priority > neighbor_priority)
        {
            lock_req req = make_lock_req(task.nghCount + ((addr_t)item.neighbor << 2),
                                         (ap_uint<64>)0xFFFFFFFFu,
                                         LOCK_OP_ADD_N_RETURN_CURRENT, true,
                                         ATOMIC_MODE_WORD);
            toLock.write(req);
            update_awaiting_response.write(item.neighbor);
        }
    }

    update_awaiting_response.write(STREAM_END);
}

void drain_priority_update_responses(hls::stream<uint32_t> &update_awaiting_response,
                                     hls::stream<uint8_t> &updates_done,
                                     hls::stream<lock_resp> &fromLock)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        uint32_t vertex = update_awaiting_response.read();
        if (vertex & STREAM_END)
        {
            break;
        }

        lock_resp resp = fromLock.read();
        (void)resp;
    }

    updates_done.write(1);
}

void finish_mis_loop_helper(hls::stream<uint8_t> &buffer_done,
                            hls::stream<uint8_t> &updates_done,
                            hls::stream<uint64_t> &argOut,
                            mis_loop_helper_args &task)
{
    buffer_done.read();
    updates_done.read();

    bool sent = false;
    while (!sent)
    {
#pragma HLS PIPELINE off
        sent = argOut.write_nb(task.cont);
    }
}

// ---------------------------------------------------------
// Top Level MIS Loop Helper
// ---------------------------------------------------------

void mis_loop_helper(void *mem_0, void *mem_1, void *mem_2, void *mem_3,
                     void *mem_4, void *mem_5, void *mem_6,
                     hls::stream<mis_loop_helper_args> &taskIn,
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
    3 latency = 48 num_write_outstanding = 16 num_read_outstanding =    \
        16 max_write_burst_length = 16 max_read_burst_length =          \
            16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_4 bundle = gmem channel = \
    4 latency = 48 num_write_outstanding = 1 num_read_outstanding =     \
        64 max_read_burst_length = 16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_5 bundle = gmem channel = \
    5 latency = 48 num_write_outstanding = 1 num_read_outstanding =     \
        64 max_read_burst_length = 16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_6 bundle = gmem channel = \
    6 latency = 48 num_write_outstanding = 1 num_read_outstanding =     \
        64 max_read_burst_length = 16 max_widen_bitwidth = 256

    auto task = taskIn.read();

    hls::stream<uint32_t> vertices("mis_vertices");
    hls::stream<uint32_t> selected_vertices("selected_vertices");
    hls::stream<uint32_t> cover_candidates("cover_candidates");
    hls::stream<uint32_t> cover_awaiting_response("cover_awaiting_response");
    hls::stream<uint32_t> newly_covered_for_buffer("newly_covered_for_buffer");
    hls::stream<uint32_t> newly_covered_for_update("newly_covered_for_update");
    hls::stream<neighbor_item> neighbors_to_update("neighbors_to_update");
    hls::stream<uint32_t> update_awaiting_response("update_awaiting_response");
    hls::stream<uint8_t> buffer_done("buffer_done");
    hls::stream<uint8_t> updates_done("updates_done");

#pragma HLS STREAM variable = vertices depth = 64
#pragma HLS STREAM variable = selected_vertices depth = 64
#pragma HLS STREAM variable = cover_candidates depth = 256
#pragma HLS STREAM variable = cover_awaiting_response depth = 256
#pragma HLS STREAM variable = newly_covered_for_buffer depth = 128
#pragma HLS STREAM variable = newly_covered_for_update depth = 128
#pragma HLS STREAM variable = neighbors_to_update depth = 256
#pragma HLS STREAM variable = update_awaiting_response depth = 128
#pragma HLS STREAM variable = buffer_done depth = 2
#pragma HLS STREAM variable = updates_done depth = 2

#pragma HLS DATAFLOW
    mis_read_vertices(vertices, task);
    select_uncovered_zero_vertices(mem_0, mem_5, vertices, selected_vertices,
                                   task);
    expand_selected_vertices(mem_1, selected_vertices, cover_candidates, task);
    attempt_cover_vertices(cover_candidates, cover_awaiting_response, task, toLock0);
    receive_cover_responses(cover_awaiting_response, newly_covered_for_buffer,
                            newly_covered_for_update, task, fromLock0, toLock1);
    write_newly_covered(mem_3, newly_covered_for_buffer, buffer_done, task, fromLock1);
    read_newly_covered_neighbors(mem_2, newly_covered_for_update,
                                 neighbors_to_update, task);
    attempt_priority_updates(mem_4, mem_6, neighbors_to_update,
                             update_awaiting_response, task, toLock2);
    drain_priority_update_responses(update_awaiting_response, updates_done,
                                    fromLock2);
    finish_mis_loop_helper(buffer_done, updates_done, argOut, task);
}
