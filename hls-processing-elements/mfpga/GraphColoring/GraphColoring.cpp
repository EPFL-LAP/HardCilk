#include "ap_int.h"
#include "util.h"
#include <cstdint>
#include <stdint.h>

#define VERTICES_PER_TASK 64
#ifndef MAX_COLORS
#define MAX_COLORS 1024
#endif
#ifndef COLOR_BITS
#define COLOR_BITS 10
#endif

static_assert(MAX_COLORS <= (1u << COLOR_BITS),
              "COLOR_BITS must represent every configured color");

using color_id_t = ap_uint<COLOR_BITS>;

static const uint32_t STREAM_END = 0x80000000u;
static const uint32_t UNCOLORED = 0xFFFFFFFFu;

typedef struct
{
    uint32_t vertex;
    uint32_t count;
} count_item;

typedef struct
{
    uint32_t vertex;
    uint32_t neighbor;
    uint32_t vertex_log_degree;
    uint32_t vertex_rank;
    bool last;
} predecessor_request;

typedef struct
{
    uint32_t vertex;
    bool predecessor;
    bool last;
} predecessor_mark;

typedef struct
{
    uint32_t vertex;
    color_id_t color;
} colored_vertex;

typedef struct
{
    uint32_t vertex;
    uint32_t neighbor;
    bool last;
} neighbor_color_request;

typedef struct
{
    uint32_t vertex;
    color_id_t color;
    bool colored;
    bool last;
} neighbor_color_mark;

typedef struct
{
    bool valid;
    color_id_t color;
} color_choice;

template <int LO, int N>
struct FirstFreeColorTree
{
    static color_choice eval(const ap_uint<MAX_COLORS> &used, uint32_t max_colors)
    {
#pragma HLS INLINE
        color_choice left = FirstFreeColorTree<LO, N / 2>::eval(used, max_colors);
        color_choice right =
            FirstFreeColorTree<LO + (N / 2), N - (N / 2)>::eval(used, max_colors);
        return left.valid ? left : right;
    }
};

template <int LO>
struct FirstFreeColorTree<LO, 1>
{
    static color_choice eval(const ap_uint<MAX_COLORS> &used, uint32_t max_colors)
    {
#pragma HLS INLINE
        color_choice choice;
        choice.valid = ((uint32_t)LO < max_colors) && (used[LO] == 0);
        choice.color = LO;
        return choice;
    }
};

static inline color_id_t first_free_color(const ap_uint<MAX_COLORS> &used,
                                          uint32_t max_colors)
{
#pragma HLS INLINE
    color_choice choice = FirstFreeColorTree<0, MAX_COLORS>::eval(used, max_colors);
    return choice.valid ? choice.color : (color_id_t)0;
}

static inline uint32_t floor_log2_u32(uint32_t x)
{
#pragma HLS INLINE
    uint32_t out = 0;
    for (uint32_t b = 0; b < 32; b++)
    {
#pragma HLS UNROLL
        if (x >> b)
            out = b;
    }
    return out;
}

static inline addr_t roots_buffer(GraphColoring_args &task, uint32_t active)
{
#pragma HLS INLINE
    return active == 0 ? task.roots0 : task.roots1;
}

static inline bool runs_before(uint32_t left_log_degree, uint32_t left_rank,
                               uint32_t right_log_degree, uint32_t right_rank)
{
#pragma HLS INLINE
    return left_log_degree > right_log_degree ||
           (left_log_degree == right_log_degree && left_rank < right_rank);
}

void init_read_vertices(hls::stream<uint32_t> &output_vertices,
                        color_init_helper_args &task)
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

void read_predecessor_neighbors(void *mem_graph, void *mem_rank,
                                hls::stream<uint32_t> &input_vertices,
                                hls::stream<predecessor_request> &predecessor_requests,
                                color_init_helper_args &task)
{
    while (true)
    {
        uint32_t vertex = input_vertices.read();
        if (vertex & STREAM_END)
            break;

        ap_uint<128> vertex_bulk =
            MEM_IN(mem_graph, task.graph + ((addr_t)vertex << 4), ap_uint<128>);
        addr_t neighbor_address = vertex_bulk.range(63, 0);
        uint32_t degree = vertex_bulk.range(127, 64);
        uint32_t vertex_log_degree = floor_log2_u32(degree);
        uint32_t vertex_rank = MEM_ARR_IN(mem_rank, task.rank, vertex, uint32_t);

        for (uint32_t n = 0; n < degree; n++)
        {
#pragma HLS pipeline II = 1
            predecessor_request request;
            request.vertex = vertex;
            request.neighbor = MEM_ARR_IN(mem_graph, neighbor_address, n, uint32_t);
            request.vertex_log_degree = vertex_log_degree;
            request.vertex_rank = vertex_rank;
            request.last = false;
            predecessor_requests.write(request);
        }

        predecessor_request end_vertex;
        end_vertex.vertex = vertex;
        end_vertex.neighbor = 0;
        end_vertex.vertex_log_degree = vertex_log_degree;
        end_vertex.vertex_rank = vertex_rank;
        end_vertex.last = true;
        predecessor_requests.write(end_vertex);
    }

    predecessor_request sentinel;
    sentinel.vertex = STREAM_END;
    sentinel.neighbor = 0;
    sentinel.vertex_log_degree = 0;
    sentinel.vertex_rank = 0;
    sentinel.last = true;
    predecessor_requests.write(sentinel);
}

void check_predecessors(void *mem_graph, void *mem_rank,
                        hls::stream<predecessor_request> &predecessor_requests,
                        hls::stream<predecessor_mark> &predecessor_marks,
                        color_init_helper_args &task)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        predecessor_request request = predecessor_requests.read();
        if (request.vertex & STREAM_END)
            break;

        predecessor_mark mark;
        mark.vertex = request.vertex;
        mark.last = request.last;
        if (request.last)
        {
            mark.predecessor = false;
        }
        else
        {
            ap_uint<128> neighbor_bulk =
                MEM_IN(mem_graph, task.graph + ((addr_t)request.neighbor << 4), ap_uint<128>);
            uint32_t neighbor_degree = neighbor_bulk.range(127, 64);
            uint32_t neighbor_log_degree = floor_log2_u32(neighbor_degree);
            uint32_t neighbor_rank =
                MEM_ARR_IN(mem_rank, task.rank, request.neighbor, uint32_t);

            mark.predecessor = runs_before(neighbor_log_degree, neighbor_rank,
                                           request.vertex_log_degree,
                                           request.vertex_rank);
        }
        predecessor_marks.write(mark);
    }

    predecessor_mark sentinel;
    sentinel.vertex = STREAM_END;
    sentinel.predecessor = false;
    sentinel.last = true;
    predecessor_marks.write(sentinel);
}

void accumulate_predecessor_counts(hls::stream<predecessor_mark> &predecessor_marks,
                                   hls::stream<count_item> &output_counts,
                                   color_init_helper_args &task)
{
    uint32_t count = 0;

    while (true)
    {
#pragma HLS pipeline II = 1
        predecessor_mark mark = predecessor_marks.read();
        if (mark.vertex & STREAM_END)
            break;

        if (!mark.last)
        {
            if (mark.predecessor)
                count++;
        }
        else
        {
            count_item item;
            item.vertex = mark.vertex;
            item.count = count;
            output_counts.write(item);
            count = 0;
        }
    }

    count_item sentinel;
    sentinel.vertex = STREAM_END;
    sentinel.count = 0;
    output_counts.write(sentinel);
}

void write_counts_and_request_roots(void *mem_priority, void *mem_color,
                                    hls::stream<count_item> &input_counts,
                                    hls::stream<uint32_t> &root_awaiting_response,
                                    color_init_helper_args &task,
                                    hls::stream<lock_req> &toLock)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        count_item item = input_counts.read();
        if (item.vertex & STREAM_END)
            break;

        MEM_ARR_OUT(mem_priority, task.priority, item.vertex, uint32_t, item.count);
        MEM_ARR_OUT(mem_color, task.color, item.vertex, uint32_t, UNCOLORED);

        if (item.count == 0)
        {
            lock_req req = make_lock_req(task.nextFChar, 1,
                                         LOCK_OP_ADD_N_RETURN_CURRENT, true,
                                         ATOMIC_MODE_DOUBLEWORD);
            toLock.write(req);
            root_awaiting_response.write(item.vertex);
        }
    }

    root_awaiting_response.write(STREAM_END);
}

void write_initial_roots(void *mem, hls::stream<uint32_t> &root_awaiting_response,
                         hls::stream<uint8_t> &roots_done,
                         color_init_helper_args &task,
                         hls::stream<lock_resp> &fromLock)
{
    uint32_t last_slot = 0;
    bool wrote_any = false;

    while (true)
    {
#pragma HLS pipeline II = 1
        uint32_t vertex = root_awaiting_response.read();
        if (vertex & STREAM_END)
            break;

        lock_resp resp = fromLock.read();
        if (lock_resp_success(resp))
        {
            uint32_t slot = lock_resp_current(resp);
            MEM_ARR_OUT(mem, task.roots, slot, uint32_t, vertex);
            last_slot = slot;
            wrote_any = true;
        }
    }

    if (wrote_any)
    {
        volatile uint32_t flush =
            MEM_ARR_IN_VOLATILE(mem, task.roots, last_slot, uint32_t);
        if (flush == 0xFFFFFFFFu)
            roots_done.write(0);
    }
    roots_done.write(1);
}

void finish_init_helper(hls::stream<uint8_t> &roots_done,
                        hls::stream<uint64_t> &argOut,
                        color_init_helper_args &task)
{
    roots_done.read();

    bool sent = false;
    while (!sent)
    {
#pragma HLS PIPELINE off
        sent = argOut.write_nb(task.cont);
    }
}

void color_init_helper(void *mem_0, void *mem_1, void *mem_2, void *mem_3,
                       void *mem_4, void *mem_5, void *mem_6,
                       hls::stream<color_init_helper_args> &taskIn,
                       hls::stream<uint64_t> &argOut,
                       hls::stream<lock_req> &toLock,
                       hls::stream<lock_resp> &fromLock)
{
#pragma HLS INTERFACE ap_ctrl_none port = return

#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = argOut
#pragma HLS INTERFACE mode = axis port = toLock
#pragma HLS INTERFACE mode = axis port = fromLock

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

    hls::stream<uint32_t> vertices("init_vertices");
    hls::stream<predecessor_request> predecessor_requests("predecessor_requests");
    hls::stream<predecessor_mark> predecessor_marks("predecessor_marks");
    hls::stream<count_item> counts("init_counts");
    hls::stream<uint32_t> root_awaiting_response("root_awaiting_response");
    hls::stream<uint8_t> roots_done("roots_done");

#pragma HLS STREAM variable = vertices depth = 64
#pragma HLS STREAM variable = predecessor_requests depth = 256
#pragma HLS STREAM variable = predecessor_marks depth = 256
#pragma HLS STREAM variable = counts depth = 64
#pragma HLS STREAM variable = root_awaiting_response depth = 128
#pragma HLS STREAM variable = roots_done depth = 2

#pragma HLS DATAFLOW
    init_read_vertices(vertices, task);
    read_predecessor_neighbors(mem_0, mem_1, vertices, predecessor_requests, task);
    check_predecessors(mem_2, mem_3, predecessor_requests, predecessor_marks,
                       task);
    accumulate_predecessor_counts(predecessor_marks, counts, task);
    write_counts_and_request_roots(mem_4, mem_6, counts, root_awaiting_response,
                                   task, toLock);
    write_initial_roots(mem_5, root_awaiting_response, roots_done, task,
                        fromLock);
    finish_init_helper(roots_done, argOut, task);
}

void GraphColoring(void *mem_0,
                   hls::stream<color_init_helper_args> &taskOutGlobal,
                   hls::stream<color_loop_helper_args> &taskOutGlobal_1,
                   hls::stream<GraphColoring_args> &taskIn)
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
    uint32_t vertex_chunks =
        (task.vertex_count + VERTICES_PER_TASK - 1) / VERTICES_PER_TASK;
    bool launch_init = task.init_done == 0;

    if (launch_init)
    {
        MEM_OUT_VOLATILE(mem_0, task.nextFChar, uint64_t, 0);
        MEM_OUT_VOLATILE(mem_0, task.colorsUsed, uint32_t, 0);
        task.init_done = 1;
        task.active = 0;
        task.done = (task.vertex_count == 0) ? 1 : 0;
        task.finished = 0;
        task.frontier_length = 0;
        task.counter = task.done ? 1 : vertex_chunks;
    }
    else
    {
        uint64_t next_length = MEM_IN_VOLATILE(mem_0, task.nextFChar, uint64_t);
        MEM_OUT_VOLATILE(mem_0, task.nextFChar, uint64_t, 0);
        task.frontier_length = (uint32_t)next_length;

        if (task.frontier_length == 0 || task.finished >= task.vertex_count)
        {
            task.done = 1;
            task.counter = 1;
        }
        else
        {
            task.done = 0;
            task.finished += task.frontier_length;
            task.counter =
                (task.frontier_length + VERTICES_PER_TASK - 1) / VERTICES_PER_TASK;
        }
    }

    uint32_t current_active = task.active;
    uint32_t next_active = launch_init ? task.active : (1 - task.active);

    // Need to do 1 at a time so that the counter gets written last.
    MEM_OUT(mem_0, task.cont + 4, uint32_t, task.vertex_count);
    MEM_OUT(mem_0, task.cont + 8, uint32_t, task.init_done);
    MEM_OUT(mem_0, task.cont + 12, uint32_t, next_active);
    MEM_OUT(mem_0, task.cont + 16, uint32_t, task.done);
    MEM_OUT(mem_0, task.cont + 20, uint32_t, task.finished);
    MEM_OUT(mem_0, task.cont + 24, uint32_t, task.frontier_length);
    MEM_OUT(mem_0, task.cont + 28, uint32_t, task.max_colors);
    MEM_OUT(mem_0, task.cont + 32, addr_t, task.graph);
    MEM_OUT(mem_0, task.cont + 40, addr_t, task.rank);
    MEM_OUT(mem_0, task.cont + 48, addr_t, task.priority);
    MEM_OUT(mem_0, task.cont + 56, addr_t, task.color);
    MEM_OUT(mem_0, task.cont + 64, addr_t, task.roots0);
    MEM_OUT(mem_0, task.cont + 72, addr_t, task.roots1);
    MEM_OUT(mem_0, task.cont + 80, addr_t, task.nextFChar);
    MEM_OUT(mem_0, task.cont + 88, addr_t, task.colorsUsed);
    MEM_OUT(mem_0, task.cont + 96, addr_t, task.cont);
    // Otherwise the counter would get written first.
    MEM_OUT(mem_0, task.cont, uint32_t, task.counter);

    if (task.done)
        return;

    if (launch_init)
    {
        for (uint32_t i = 0; i < task.vertex_count; i += VERTICES_PER_TASK)
        {
#pragma HLS PIPELINE II = 1
            color_init_helper_args init_task;
            init_task.graph = task.graph;
            init_task.rank = task.rank;
            init_task.priority = task.priority;
            init_task.color = task.color;
            init_task.roots = task.roots0;
            init_task.nextFChar = task.nextFChar;
            init_task.cont = task.cont;
            init_task.index = i;
            init_task.vertex_count = task.vertex_count;
            init_task.task_vertex_count =
                (task.vertex_count - i) > VERTICES_PER_TASK ? VERTICES_PER_TASK : (task.vertex_count - i);
            init_task.max_colors = task.max_colors;
            taskOutGlobal.write(init_task);
        }
        return;
    }

    addr_t current_roots = roots_buffer(task, current_active);
    addr_t next_roots = roots_buffer(task, next_active);
    for (uint32_t i = 0; i < task.frontier_length; i += VERTICES_PER_TASK)
    {
#pragma HLS PIPELINE II = 1
        color_loop_helper_args loop_task;
        loop_task.graph = task.graph;
        loop_task.priority = task.priority;
        loop_task.color = task.color;
        loop_task.current_roots = current_roots;
        loop_task.next_roots = next_roots;
        loop_task.nextFChar = task.nextFChar;
        loop_task.colorsUsed = task.colorsUsed;
        loop_task.cont = task.cont;
        loop_task.index = i;
        loop_task.frontier_length = task.frontier_length;
        loop_task.task_vertex_count =
            (task.frontier_length - i) > VERTICES_PER_TASK ? VERTICES_PER_TASK : (task.frontier_length - i);
        loop_task.max_colors = task.max_colors;
        loop_task.vertex_count = task.vertex_count;
        taskOutGlobal_1.write(loop_task);
    }
}

void loop_read_roots(void *mem, hls::stream<uint32_t> &output_roots,
                     color_loop_helper_args &task)
{
    for (uint32_t i = 0; i < VERTICES_PER_TASK; i++)
    {
#pragma HLS pipeline II = 1
        if (i < task.task_vertex_count && task.index + i < task.frontier_length)
        {
            uint32_t root =
                MEM_ARR_IN(mem, task.current_roots, task.index + i, uint32_t);
            output_roots.write(root);
        }
    }

    output_roots.write(STREAM_END);
}

void read_root_neighbors_for_colors(void *mem_graph,
                                    hls::stream<uint32_t> &input_roots,
                                    hls::stream<neighbor_color_request> &color_requests,
                                    color_loop_helper_args &task)
{
    while (true)
    {
        uint32_t vertex = input_roots.read();
        if (vertex & STREAM_END)
            break;

        ap_uint<128> bulk =
            MEM_IN(mem_graph, task.graph + ((addr_t)vertex << 4), ap_uint<128>);
        addr_t neighbor_address = bulk.range(63, 0);
        uint32_t degree = bulk.range(127, 64);

        for (uint32_t n = 0; n < degree; n++)
        {
#pragma HLS pipeline II = 1
            neighbor_color_request request;
            request.vertex = vertex;
            request.neighbor = MEM_ARR_IN(mem_graph, neighbor_address, n, uint32_t);
            request.last = false;
            color_requests.write(request);
        }

        neighbor_color_request end_vertex;
        end_vertex.vertex = vertex;
        end_vertex.neighbor = 0;
        end_vertex.last = true;
        color_requests.write(end_vertex);
    }

    neighbor_color_request sentinel;
    sentinel.vertex = STREAM_END;
    sentinel.neighbor = 0;
    sentinel.last = true;
    color_requests.write(sentinel);
}

void read_neighbor_colors(void *mem_color,
                          hls::stream<neighbor_color_request> &color_requests,
                          hls::stream<neighbor_color_mark> &color_marks,
                          color_loop_helper_args &task)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        neighbor_color_request request = color_requests.read();
        if (request.vertex & STREAM_END)
            break;

        neighbor_color_mark mark;
        mark.vertex = request.vertex;
        mark.last = request.last;
        if (request.last)
        {
            mark.colored = false;
            mark.color = 0;
        }
        else
        {
            uint32_t color =
                MEM_ARR_IN(mem_color, task.color, request.neighbor, uint32_t);
            mark.colored = color != UNCOLORED && color < MAX_COLORS;
            mark.color = color;
        }
        color_marks.write(mark);
    }

    neighbor_color_mark sentinel;
    sentinel.vertex = STREAM_END;
    sentinel.color = 0;
    sentinel.colored = false;
    sentinel.last = true;
    color_marks.write(sentinel);
}

void choose_and_write_colors(void *mem_color,
                             hls::stream<neighbor_color_mark> &color_marks,
                             hls::stream<colored_vertex> &colored_for_max,
                             hls::stream<uint32_t> &colored_for_updates,
                             color_loop_helper_args &task)
{
    ap_uint<MAX_COLORS> used = 0;
    uint32_t last_vertex = 0;
    bool wrote_any = false;

    while (true)
    {
#pragma HLS pipeline II = 1
        neighbor_color_mark mark = color_marks.read();
        if (mark.vertex & STREAM_END)
            break;

        if (!mark.last)
        {
            if (mark.colored)
            {
                used[mark.color] = 1;
            }
        }
        else
        {
            color_id_t chosen = first_free_color(used, task.max_colors);
            MEM_ARR_OUT(mem_color, task.color, mark.vertex, uint32_t,
                        (uint32_t)chosen);
            last_vertex = mark.vertex;
            wrote_any = true;

            colored_vertex colored;
            colored.vertex = mark.vertex;
            colored.color = chosen;
            colored_for_max.write(colored);
            colored_for_updates.write(mark.vertex);

            used = 0;
        }
    }

    if (wrote_any)
    {
        volatile uint32_t flush =
            MEM_ARR_IN_VOLATILE(mem_color, task.color, last_vertex, uint32_t);
        if (flush == 0xFFFFFFFFu)
            colored_for_updates.write(STREAM_END);
    }

    colored_vertex sentinel;
    sentinel.vertex = STREAM_END;
    sentinel.color = 0;
    colored_for_max.write(sentinel);
    colored_for_updates.write(STREAM_END);
}

void request_colors_used_updates(hls::stream<colored_vertex> &colored_vertices,
                                 hls::stream<uint32_t> &colors_used_awaiting_response,
                                 color_loop_helper_args &task,
                                 hls::stream<lock_req> &toLock)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        colored_vertex colored = colored_vertices.read();
        if (colored.vertex & STREAM_END)
            break;

        lock_req req = make_lock_req(task.colorsUsed,
                                     (ap_uint<64>)((uint32_t)colored.color + 1),
                                     LOCK_OP_SET_IF_GREATER_AND_RETURN_CURRENT,
                                     true, ATOMIC_MODE_WORD);
        toLock.write(req);
        colors_used_awaiting_response.write(colored.vertex);
    }

    colors_used_awaiting_response.write(STREAM_END);
}

void drain_colors_used_responses(hls::stream<uint32_t> &colors_used_awaiting_response,
                                 hls::stream<uint8_t> &colors_used_done,
                                 hls::stream<lock_resp> &fromLock)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        uint32_t vertex = colors_used_awaiting_response.read();
        if (vertex & STREAM_END)
            break;

        lock_resp resp = fromLock.read();
        (void)resp;
    }

    colors_used_done.write(1);
}

void read_colored_neighbors(void *mem, hls::stream<uint32_t> &colored_roots,
                            hls::stream<uint32_t> &neighbors_to_decrement,
                            color_loop_helper_args &task)
{
    while (true)
    {
        uint32_t vertex = colored_roots.read();
        if (vertex & STREAM_END)
            break;

        ap_uint<128> bulk =
            MEM_IN(mem, task.graph + ((addr_t)vertex << 4), ap_uint<128>);
        addr_t neighbor_address = bulk.range(63, 0);
        uint32_t degree = bulk.range(127, 64);

        for (uint32_t n = 0; n < degree; n++)
        {
#pragma HLS pipeline II = 1
            uint32_t neighbor = MEM_ARR_IN(mem, neighbor_address, n, uint32_t);
            neighbors_to_decrement.write(neighbor);
        }
    }

    neighbors_to_decrement.write(STREAM_END);
}

void attempt_priority_decrements(void *mem,
                                 hls::stream<uint32_t> &neighbors_to_decrement,
                                 hls::stream<uint32_t> &decrement_awaiting_response,
                                 color_loop_helper_args &task,
                                 hls::stream<lock_req> &toLock)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        uint32_t neighbor = neighbors_to_decrement.read();
        if (neighbor & STREAM_END)
            break;

        uint32_t priority =
            MEM_ARR_IN(mem, task.priority, neighbor, uint32_t);
        if (priority > 0)
        {
            lock_req req = make_lock_req(task.priority + ((addr_t)neighbor << 2),
                                         (ap_uint<64>)0xFFFFFFFFu,
                                         LOCK_OP_ADD_N_RETURN_CURRENT, true,
                                         ATOMIC_MODE_WORD);
            toLock.write(req);
            decrement_awaiting_response.write(neighbor);
        }
    }

    decrement_awaiting_response.write(STREAM_END);
}

void receive_decrement_responses(hls::stream<uint32_t> &decrement_awaiting_response,
                                 hls::stream<uint32_t> &new_roots,
                                 color_loop_helper_args &task,
                                 hls::stream<lock_resp> &fromLock,
                                 hls::stream<lock_req> &toLock)
{
    while (true)
    {
#pragma HLS pipeline II = 1
        uint32_t neighbor = decrement_awaiting_response.read();
        if (neighbor & STREAM_END)
            break;

        lock_resp resp = fromLock.read();
        if (lock_resp_success(resp) && (uint32_t)lock_resp_current(resp) == 1)
        {
            lock_req req = make_lock_req(task.nextFChar, 1,
                                         LOCK_OP_ADD_N_RETURN_CURRENT, true,
                                         ATOMIC_MODE_DOUBLEWORD);
            toLock.write(req);
            new_roots.write(neighbor);
        }
    }

    new_roots.write(STREAM_END);
}

void write_next_roots(void *mem, hls::stream<uint32_t> &new_roots,
                      hls::stream<uint8_t> &roots_done,
                      color_loop_helper_args &task,
                      hls::stream<lock_resp> &fromLock)
{
    uint32_t last_slot = 0;
    bool wrote_any = false;

    while (true)
    {
#pragma HLS pipeline II = 1
        uint32_t vertex = new_roots.read();
        if (vertex & STREAM_END)
            break;

        lock_resp resp = fromLock.read();
        if (lock_resp_success(resp))
        {
            uint32_t slot = lock_resp_current(resp);
            MEM_ARR_OUT(mem, task.next_roots, slot, uint32_t, vertex);
            last_slot = slot;
            wrote_any = true;
        }
    }

    if (wrote_any)
    {
        volatile uint32_t flush =
            MEM_ARR_IN_VOLATILE(mem, task.next_roots, last_slot, uint32_t);
        if (flush == 0xFFFFFFFFu)
            roots_done.write(0);
    }
    roots_done.write(1);
}

void finish_color_loop_helper(hls::stream<uint8_t> &colors_used_done,
                              hls::stream<uint8_t> &roots_done,
                              hls::stream<uint64_t> &argOut,
                              color_loop_helper_args &task)
{
    colors_used_done.read();
    roots_done.read();

    bool sent = false;
    while (!sent)
    {
#pragma HLS PIPELINE off
        sent = argOut.write_nb(task.cont);
    }
}

void color_loop_helper(void *mem_0, void *mem_1, void *mem_2, void *mem_3,
                       void *mem_4, void *mem_5, void *mem_6,
                       hls::stream<color_loop_helper_args> &taskIn,
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
    1 latency = 48 num_write_outstanding = 16 num_read_outstanding =    \
        16 max_write_burst_length = 16 max_read_burst_length =          \
            16 max_widen_bitwidth = 256
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
    6 latency = 48 num_write_outstanding = 16 num_read_outstanding =    \
        16 max_write_burst_length = 16 max_read_burst_length =          \
            16 max_widen_bitwidth = 256

    auto task = taskIn.read();

    hls::stream<uint32_t> roots("roots");
    hls::stream<neighbor_color_request> color_requests("color_requests");
    hls::stream<neighbor_color_mark> color_marks("color_marks");
    hls::stream<colored_vertex> colored_for_max("colored_for_max");
    hls::stream<uint32_t> colored_for_updates("colored_for_updates");
    hls::stream<uint32_t> colors_used_awaiting_response("colors_used_awaiting_response");
    hls::stream<uint32_t> neighbors_to_decrement("neighbors_to_decrement");
    hls::stream<uint32_t> decrement_awaiting_response("decrement_awaiting_response");
    hls::stream<uint32_t> new_roots("new_roots");
    hls::stream<uint8_t> colors_used_done("colors_used_done");
    hls::stream<uint8_t> roots_done("roots_done");

#pragma HLS STREAM variable = roots depth = 64
#pragma HLS STREAM variable = color_requests depth = 256
#pragma HLS STREAM variable = color_marks depth = 256
#pragma HLS STREAM variable = colored_for_max depth = 256
#pragma HLS STREAM variable = colored_for_updates depth = 256
#pragma HLS STREAM variable = colors_used_awaiting_response depth = 256
#pragma HLS STREAM variable = neighbors_to_decrement depth = 512
#pragma HLS STREAM variable = decrement_awaiting_response depth = 256
#pragma HLS STREAM variable = new_roots depth = 256
#pragma HLS STREAM variable = colors_used_done depth = 8
#pragma HLS STREAM variable = roots_done depth = 8

#pragma HLS DATAFLOW
    loop_read_roots(mem_0, roots, task);
    read_root_neighbors_for_colors(mem_1, roots, color_requests, task);
    read_neighbor_colors(mem_2, color_requests, color_marks, task);
    choose_and_write_colors(mem_3, color_marks, colored_for_max,
                            colored_for_updates, task);
    request_colors_used_updates(colored_for_max, colors_used_awaiting_response,
                                task, toLock2);
    drain_colors_used_responses(colors_used_awaiting_response, colors_used_done,
                                fromLock2);
    read_colored_neighbors(mem_4, colored_for_updates, neighbors_to_decrement,
                           task);
    attempt_priority_decrements(mem_5, neighbors_to_decrement,
                                decrement_awaiting_response, task, toLock0);
    receive_decrement_responses(decrement_awaiting_response, new_roots, task,
                                fromLock0, toLock1);
    write_next_roots(mem_6, new_roots, roots_done, task, fromLock1);
    finish_color_loop_helper(colors_used_done, roots_done, argOut, task);
}
