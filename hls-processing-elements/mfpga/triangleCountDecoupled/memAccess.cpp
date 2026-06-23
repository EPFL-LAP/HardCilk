#include "hls_stream.h"
#include "memAccess_defs.h"
#include <ap_int.h>
#include <stdint.h>

#define N_whileLoopMain_reentry0_cont0 4
#define N_memReader 4
#define N_whileLoopMain_reentry0 4

// Instantiate watcher elsewhere

// Raw AXIS handshake for one queue. Two scalar fields (NOT a single ap_uint<2>):
// an aggregate volatile read of an ap_uint<2> port gets hoisted by HLS (sampled
// once -> the watcher never detects a state change), whereas per-field scalar
// volatile reads are not hoisted. Coherence across the two bits comes from the
// single uniform RegNext stage in connectWatcher (the port is already a stable
// registered snapshot). bit0 = valid, bit1 = ready; connectWatcher drives
// Cat(ready, valid) so the first struct field (valid) is bit0.
struct QueueStatus
{
  bool valid;
  bool ready;
};

struct PEStatus
{
  QueueStatus in;  // PE INPUT queue  (consumer side)
  QueueStatus out; // PE OUTPUT queue (producer side)
};

// ====================================================================
// Telemetry bundle format -- BIT-PACKED 128-bit words (NOT natural layout)
// ====================================================================
// One 256-bit AXI beat carries TWO 128-bit bundles: beat = {slot1, slot0}.
// write_idx counts 256-bit beats (one store/cycle => II=1; the spare slot is a
// NULL bundle the host skips). Each 128-bit bundle is [7:0] = header (type):
//   H_NULL   (0)        empty slot, host skips
//   H_STATUS (1)        [55:8]  = 48 PE status bits (12 PEs x 4: in_v,in_r,out_v,out_r)
//                       [127:56]= 72-bit cycle_count (since the start gate)
//   H_BW_R+s (2,3,4)    read  avg bytes/cycle, sub-bundle s: [127:8] = 15 x 8-bit
//   H_BW_W+s (5,6,7)    write avg bytes/cycle, sub-bundle s: [127:8] = 15 x 8-bit
//                       port p -> sub (p/15), slot (p%15) at bits [8 + slot*8 +: 8]
//   H_BW_ADDR(8)        [12:8]=port idx, [32:13]=AW top20, [52:33]=AR top20 (rotating)
// BW averages are accumulated byte-accurately and divided by the window (a
// power-of-2 => free >>WIN_SHIFT shift, no HLS divider in the II=1 loop).
#define MAX_HBM_PORTS 31
#define WINDOW 128 // power of 2 => avg = total >> WIN_SHIFT
#define WIN_SHIFT 7
#define PORTS_PER_BW 15 // 15 x 8-bit + 8-bit header = 128 bits
#define N_BW_SUB 3      // ceil(MAX_HBM_PORTS / PORTS_PER_BW)
#define BWQ_DEPTH 8     // 2*N_BW_SUB + 1 (addr) = 7 bundles/window, fits 8

#define H_NULL 0
#define H_STATUS 1
#define H_BW_R 2 // 2,3,4
#define H_BW_W 5 // 5,6,7
#define H_BW_ADDR 8

// --------------------------------------------------------
// WATCHER KERNEL
// --------------------------------------------------------

void watcher(
    ap_uint<256> *mem,   // 256-bit AXI beat = two 128-bit bundle slots {slot1,slot0}
    uint64_t start_addr, // byte offset provided by the host
    // Status: each queue is ONE 2-bit port {bit0=valid,bit1=ready}, read ONCE per
    // iteration via a DIRECT volatile pointer (not a struct member). Reading a port
    // twice (per-field) creates a loop-carried volatile dependence => II=2; reading
    // through a direct volatile pointer keeps the read fresh (no hoist) at II=1.
    ap_uint<2> cont0_status_in[N_whileLoopMain_reentry0_cont0],
    ap_uint<2> cont0_status_out[N_whileLoopMain_reentry0_cont0],
    ap_uint<2> memReader_status_in[N_memReader],
    ap_uint<2> memReader_status_out[N_memReader],
    ap_uint<2> reentry0_status_in[N_whileLoopMain_reentry0],
    ap_uint<2> reentry0_status_out[N_whileLoopMain_reentry0],
    // --- per-HBM-port bandwidth taps (already byte-accurate, computed in Chisel) ---
    ap_uint<8> bw_wbytes[MAX_HBM_PORTS],  // write bytes this cycle (PopCount WSTRB)
    ap_uint<16> bw_rbytes[MAX_HBM_PORTS], // read  bytes this cycle ((ARLEN+1)<<ARSIZE)
    ap_uint<20> bw_awaddr[MAX_HBM_PORTS], // most-recent AW top-20 addr bits (tapped, future)
    ap_uint<20> bw_araddr[MAX_HBM_PORTS], // most-recent AR top-20 addr bits (tapped, future)
    bool start_gate)                      // first-task-dispatch from the spawn scheduler
{
// Memory Write Port: AXI4 Master (256-bit beats)
#pragma HLS INTERFACE mode = m_axi port = mem offset = direct
#pragma HLS INTERFACE mode = ap_none port = start_addr
#pragma HLS INTERFACE ap_ctrl_none port = return

// Status: one discrete 2-bit ap_none pin per queue (<prefix>_in_<i>/<prefix>_out_<i>)
#pragma HLS ARRAY_PARTITION variable = cont0_status_in complete dim = 1
#pragma HLS INTERFACE mode = ap_none port = cont0_status_in
#pragma HLS ARRAY_PARTITION variable = cont0_status_out complete dim = 1
#pragma HLS INTERFACE mode = ap_none port = cont0_status_out
#pragma HLS ARRAY_PARTITION variable = memReader_status_in complete dim = 1
#pragma HLS INTERFACE mode = ap_none port = memReader_status_in
#pragma HLS ARRAY_PARTITION variable = memReader_status_out complete dim = 1
#pragma HLS INTERFACE mode = ap_none port = memReader_status_out
#pragma HLS ARRAY_PARTITION variable = reentry0_status_in complete dim = 1
#pragma HLS INTERFACE mode = ap_none port = reentry0_status_in
#pragma HLS ARRAY_PARTITION variable = reentry0_status_out complete dim = 1
#pragma HLS INTERFACE mode = ap_none port = reentry0_status_out

// Bandwidth + address taps: one discrete ap_none input pin per port
#pragma HLS ARRAY_PARTITION variable = bw_wbytes complete dim = 1
#pragma HLS INTERFACE mode = ap_none port = bw_wbytes
#pragma HLS ARRAY_PARTITION variable = bw_rbytes complete dim = 1
#pragma HLS INTERFACE mode = ap_none port = bw_rbytes
#pragma HLS ARRAY_PARTITION variable = bw_awaddr complete dim = 1
#pragma HLS INTERFACE mode = ap_none port = bw_awaddr
#pragma HLS ARRAY_PARTITION variable = bw_araddr complete dim = 1
#pragma HLS INTERFACE mode = ap_none port = bw_araddr
#pragma HLS INTERFACE mode = ap_none port = start_gate

  // -------- persistent state (reset-initialized to 0 / false) --------
  static bool running = false;       // start-gate latch
  static bool primed = false;        // first-write prime guard (X-address avoidance)
  static uint64_t write_idx = 0;     // counts 256-bit beats
  static uint64_t cycle_count = 0;   // cycles since the start gate opened
  static ap_uint<48> lastStatus = 0; // last 48 status bits (change detection)
  static ap_uint<24> read_acc[MAX_HBM_PORTS] = {0};
  static ap_uint<24> write_acc[MAX_HBM_PORTS] = {0};
#pragma HLS ARRAY_PARTITION variable = read_acc complete dim = 1
#pragma HLS ARRAY_PARTITION variable = write_acc complete dim = 1
  static ap_uint<8> win_cnt = 0;      // window cycle counter (0..WINDOW-1)
  static ap_uint<5> addr_rot = 0;     // rotating port for the address sample
  static ap_uint<128> bwq[BWQ_DEPTH]; // pending bundle queue (empty each window)
#pragma HLS ARRAY_PARTITION variable = bwq complete dim = 1
  static ap_uint<4> bwq_head = 0;
  static ap_uint<4> bwq_count = 0;

  // 256-bit (32-byte) beat index
  uint64_t base_idx = start_addr / 32;

  while (true)
  {
#pragma HLS pipeline II = 1

    // ---- start gate: stay fully idle until the first task is dispatched, then
    // reset all write state so the trace deterministically begins at beat 0 with
    // cycle 0 (independent of any pre-gate static value). ----
    if (!running)
    {
      if (start_gate)
      {
        running = true;
        write_idx = 0;
        cycle_count = 0;
        win_cnt = 0;
        bwq_head = 0;
        bwq_count = 0;
        addr_rot = 0;
        primed = false;
        for (int p = 0; p < MAX_HBM_PORTS; p++)
        {
#pragma HLS unroll
          read_acc[p] = 0;
          write_acc[p] = 0;
        }
      }
      else
        continue;
    }

    cycle_count++;

    // ---- volatile views: fresh read every iteration (avoids HLS hoisting) ----
    volatile ap_uint<2> *c0in_v = cont0_status_in;
    volatile ap_uint<2> *c0out_v = cont0_status_out;
    volatile ap_uint<2> *mrin_v = memReader_status_in;
    volatile ap_uint<2> *mrout_v = memReader_status_out;
    volatile ap_uint<2> *r0in_v = reentry0_status_in;
    volatile ap_uint<2> *r0out_v = reentry0_status_out;
    volatile ap_uint<8> *wbytes_v = bw_wbytes;
    volatile ap_uint<16> *rbytes_v = bw_rbytes;
    volatile ap_uint<20> *awaddr_v = bw_awaddr;
    volatile ap_uint<20> *araddr_v = bw_araddr;

    // ---- pack the 48 PE status bits: PE k at [k*4 +: 4] = {in_v,in_r,out_v,out_r} ----
    // Each queue port is read EXACTLY ONCE (single 2-bit read) so the loop stays II=1.
    ap_uint<48> curStatus = 0;
    for (int i = 0; i < N_whileLoopMain_reentry0_cont0; i++)
    {
#pragma HLS unroll
      ap_uint<2> in_raw = c0in_v[i];
      ap_uint<2> out_raw = c0out_v[i];
      curStatus(i * 4 + 1, i * 4 + 0) = in_raw;
      curStatus(i * 4 + 3, i * 4 + 2) = out_raw;
    }
    for (int i = 0; i < N_memReader; i++)
    {
#pragma HLS unroll
      ap_uint<2> in_raw = mrin_v[i];
      ap_uint<2> out_raw = mrout_v[i];
      curStatus((4 + i) * 4 + 1, (4 + i) * 4 + 0) = in_raw;
      curStatus((4 + i) * 4 + 3, (4 + i) * 4 + 2) = out_raw;
    }
    for (int i = 0; i < N_whileLoopMain_reentry0; i++)
    {
#pragma HLS unroll
      ap_uint<2> in_raw = r0in_v[i];
      ap_uint<2> out_raw = r0out_v[i];
      curStatus((8 + i) * 4 + 1, (8 + i) * 4 + 0) = in_raw;
      curStatus((8 + i) * 4 + 3, (8 + i) * 4 + 2) = out_raw;
    }
    bool state_changed = (curStatus != lastStatus);

    // ---- byte-accurate per-port accumulation + window averaging ----
    bool window = (win_cnt == (WINDOW - 1));
    ap_uint<8> avg_r[MAX_HBM_PORTS];
    ap_uint<8> avg_w[MAX_HBM_PORTS];
#pragma HLS ARRAY_PARTITION variable = avg_r complete dim = 1
#pragma HLS ARRAY_PARTITION variable = avg_w complete dim = 1
    for (int p = 0; p < MAX_HBM_PORTS; p++)
    {
#pragma HLS unroll
      ap_uint<24> r_old = read_acc[p];
      ap_uint<24> w_old = write_acc[p];
      ap_uint<16> r_inc = rbytes_v[p];
      ap_uint<8> w_inc = wbytes_v[p];
      // average over the window uses the OLD accumulator (before this cycle)
      ap_uint<24> r_avg = r_old >> WIN_SHIFT;
      ap_uint<24> w_avg = w_old >> WIN_SHIFT;
      avg_r[p] = (r_avg > 255) ? (ap_uint<8>)255 : (ap_uint<8>)r_avg;
      avg_w[p] = (w_avg > 255) ? (ap_uint<8>)255 : (ap_uint<8>)w_avg;
      // reset to this cycle's bytes at the window boundary, else accumulate
      read_acc[p] = window ? (ap_uint<24>)r_inc : (ap_uint<24>)(r_old + r_inc);
      write_acc[p] = window ? (ap_uint<24>)w_inc : (ap_uint<24>)(w_old + w_inc);
    }
    win_cnt = window ? (ap_uint<8>)0 : (ap_uint<8>)(win_cnt + 1);

    // ---- window boundary: build the 7 BW bundles into the (empty) queue ----
    // The previous window's <=7 bundles flushed within <=7 cycles, far inside
    // the 128-cycle window, so the queue is always empty here.
    if (window)
    {
      for (int s = 0; s < N_BW_SUB; s++)
      {
#pragma HLS unroll
        ap_uint<128> rb = 0, wb = 0;
        rb(7, 0) = H_BW_R + s;
        wb(7, 0) = H_BW_W + s;
        for (int k = 0; k < PORTS_PER_BW; k++)
        {
#pragma HLS unroll
          int p = s * PORTS_PER_BW + k;
          ap_uint<8> ra = (p < MAX_HBM_PORTS) ? avg_r[p] : (ap_uint<8>)0;
          ap_uint<8> wa = (p < MAX_HBM_PORTS) ? avg_w[p] : (ap_uint<8>)0;
          rb(8 + k * 8 + 7, 8 + k * 8) = ra;
          wb(8 + k * 8 + 7, 8 + k * 8) = wa;
        }
        bwq[s] = rb;
        bwq[N_BW_SUB + s] = wb;
      }
      // rotating address sample bundle (keeps the tapped address ports alive)
      ap_uint<128> ab = 0;
      ap_uint<20> aw_sample = awaddr_v[addr_rot];
      ap_uint<20> ar_sample = araddr_v[addr_rot];
      ab(7, 0) = H_BW_ADDR;
      ab(12, 8) = addr_rot;
      ab(32, 13) = aw_sample;
      ab(52, 33) = ar_sample;
      bwq[2 * N_BW_SUB] = ab;
      bwq_head = 0;
      bwq_count = 2 * N_BW_SUB + 1; // 7
      addr_rot = (addr_rot + 1 >= MAX_HBM_PORTS) ? (ap_uint<5>)0 : (ap_uint<5>)(addr_rot + 1);
    }

    // ---- prime guard: consume the first would-be write without emitting it ----
    if (!primed)
    {
      if (state_changed)
      {
        lastStatus = curStatus;
        primed = true;
      }
      continue; // do not write or drain the queue until primed
    }

    // ---- assemble one beat: slot0 = status (if changed) else BW; slot1 = BW ----
    ap_uint<128> slot0 = 0; // header 0 = NULL
    ap_uint<128> slot1 = 0;
    bool wrote = false;
    ap_uint<4> head = bwq_head;
    ap_uint<4> cnt = bwq_count;

    if (state_changed)
    {
      ap_uint<128> st = 0;
      st(7, 0) = H_STATUS;
      st(55, 8) = curStatus;
      st(127, 56) = (ap_uint<72>)cycle_count;
      slot0 = st;
      wrote = true;
      lastStatus = curStatus;
    }
    else if (cnt > 0)
    {
      slot0 = bwq[head];
      head = head + 1;
      cnt = cnt - 1;
      wrote = true;
    }
    if (cnt > 0)
    {
      slot1 = bwq[head];
      head = head + 1;
      cnt = cnt - 1;
      wrote = true;
    }
    bwq_head = head;
    bwq_count = cnt;

    if (wrote)
    {
      ap_uint<256> beat;
      beat(127, 0) = slot0;
      beat(255, 128) = slot1;
      mem[base_idx + write_idx] = beat;
      write_idx++;
    }
  }
}

void whileLoopMain_reentry0_cont0(
    void *mem,
    hls::stream<whileLoopMain_reentry0_cont0_task> &taskIn,
    hls::stream<whileLoopMain_reentry0_task> &taskOutGlobal)
{

#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = taskOutGlobal
#pragma HLS INTERFACE mode = m_axi port = mem
#pragma HLS INTERFACE ap_ctrl_none port = return
#pragma HLS PIPELINE II = 1 style = flp
// Intentionally ignores the count RMW dependency: the next iteration must fetch
// memory operands before it can revisit this count address, which should give
// the writeback time to commit in this generated pipeline.
#pragma HLS DEPENDENCE variable = mem inter false

  whileLoopMain_reentry0_cont0_task args = taskIn.read();

  if ((args.a_i == args.b_j))
  {
    (MEM_IN(mem, args.count, int)++);
    (args.i++);
    (args.j++);
  }
  else
  {
    if ((args.a_i < args.b_j))
    {
      (args.i++);
    }
    else
    {
      (args.j++);
    }
  }
  whileLoopMain_reentry0_task whileLoopMain_reentry0_args0;
  whileLoopMain_reentry0_args0._cont = args._cont;
  whileLoopMain_reentry0_args0.A = args.A;
  whileLoopMain_reentry0_args0.B = args.B;
  whileLoopMain_reentry0_args0.count = args.count;
  whileLoopMain_reentry0_args0.size = args.size;
  whileLoopMain_reentry0_args0.i = args.i;
  whileLoopMain_reentry0_args0.j = args.j;
  whileLoopMain_reentry0_args0.a_i = args.a_i;
  whileLoopMain_reentry0_args0.b_j = args.b_j;
  taskOutGlobal.write(whileLoopMain_reentry0_args0);
}

void whileLoopMain_exit0(
    hls::stream<whileLoopMain_exit0_task> &taskIn,
    hls::stream<uint64_t> &argOut)
{

#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = argOut
#pragma HLS INTERFACE ap_ctrl_none port = return
#pragma HLS PIPELINE II = 1 style = flp

  whileLoopMain_exit0_task args = taskIn.read();

  argOut.write(args._cont);
}
void memReader(
    void *mem,
    hls::stream<memReader_task> &taskIn,
    hls::stream<uint64_t> &argOut,
    hls::stream<uint32_t_arg_out> &argDataOut)
{

#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = argOut
#pragma HLS INTERFACE mode = axis register_mode = off port = argDataOut
#pragma HLS INTERFACE mode = m_axi port = mem
#pragma HLS INTERFACE ap_ctrl_none port = return
#pragma HLS PIPELINE II = 1 style = flp

  memReader_task args = taskIn.read();
  uint32_t_arg_out a0;
  a0.addr = args._cont;
  a0.data = MEM_ARR_IN(mem, args.mem, args.idx, int);
  a0.size = 2;
  a0.allow = 1;
  argDataOut.write(a0);
  argOut.write(args._cont);
}

void whileLoopMain(
    hls::stream<whileLoopMain_task> &taskIn,
    hls::stream<whileLoopMain_reentry0_task> &taskOutGlobal)
{

#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = taskOutGlobal
#pragma HLS INTERFACE ap_ctrl_none port = return
#pragma HLS PIPELINE II = 1 style = flp

  uint32_t i;
  uint32_t j;
  uint32_t a_i;
  uint32_t b_j;
  whileLoopMain_task args = taskIn.read();

  i = 0;
  j = 0;
  whileLoopMain_reentry0_task whileLoopMain_reentry0_args1;
  whileLoopMain_reentry0_args1._cont = args._cont;
  whileLoopMain_reentry0_args1.A = args.A;
  whileLoopMain_reentry0_args1.B = args.B;
  whileLoopMain_reentry0_args1.count = args.count;
  whileLoopMain_reentry0_args1.size = args.size;
  whileLoopMain_reentry0_args1.i = i;
  whileLoopMain_reentry0_args1.j = j;
  whileLoopMain_reentry0_args1.a_i = 0;
  whileLoopMain_reentry0_args1.b_j = 0;
  taskOutGlobal.write(whileLoopMain_reentry0_args1);
}

void whileLoopMain_reentry0(
    void *mem,
    hls::stream<whileLoopMain_reentry0_task> &taskIn,
    hls::stream<memReader_task> &taskOutGlobal,
    hls::stream<uint64_t> &closureIn,
    hls::stream<whileLoopMain_reentry0_cont0_spawn_next> &spawnNext)
{

#pragma HLS INTERFACE mode = m_axi port = mem
#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = taskOutGlobal
#pragma HLS INTERFACE mode = axis port = closureIn
#pragma HLS INTERFACE mode = axis port = spawnNext
#pragma HLS INTERFACE ap_ctrl_none port = return
#pragma HLS PIPELINE II = 2 style = flp

  whileLoopMain_reentry0_task args = taskIn.read();

  if (((args.i < args.size) && (args.j < args.size)))
  {
    uint32_t SN_whileLoopMain_reentry0_cont0c_cnt = 2;
    whileLoopMain_reentry0_cont0_task SN_whileLoopMain_reentry0_cont0c;
    SN_whileLoopMain_reentry0_cont0c._cont = args._cont;
    SN_whileLoopMain_reentry0_cont0c._counter = SN_whileLoopMain_reentry0_cont0c_cnt;
    addr_t SN_whileLoopMain_reentry0_cont0c_k = closureIn.read();

    SN_whileLoopMain_reentry0_cont0c.j = args.j;
    SN_whileLoopMain_reentry0_cont0c.i = args.i;
    SN_whileLoopMain_reentry0_cont0c.size = args.size;
    SN_whileLoopMain_reentry0_cont0c.count = args.count;
    SN_whileLoopMain_reentry0_cont0c.B = args.B;
    SN_whileLoopMain_reentry0_cont0c.A = args.A;
    SN_whileLoopMain_reentry0_cont0c.a_i = args.a_i;
    SN_whileLoopMain_reentry0_cont0c.b_j = args.b_j;
    whileLoopMain_reentry0_cont0_spawn_next SN_whileLoopMain_reentry0_cont0;
    SN_whileLoopMain_reentry0_cont0.addr = SN_whileLoopMain_reentry0_cont0c_k;
    SN_whileLoopMain_reentry0_cont0.data = SN_whileLoopMain_reentry0_cont0c;
    SN_whileLoopMain_reentry0_cont0.size = 6;
    SN_whileLoopMain_reentry0_cont0.allow = SN_whileLoopMain_reentry0_cont0c_cnt;
    spawnNext.write(SN_whileLoopMain_reentry0_cont0);

    memReader_task memReader_args2;
    memReader_args2._cont = SN_whileLoopMain_reentry0_cont0c_k + offsetof(whileLoopMain_reentry0_cont0_task, a_i);
    memReader_args2.mem = args.A;
    memReader_args2.idx = args.i;
    taskOutGlobal.write(memReader_args2);

    memReader_task memReader_args3;
    memReader_args3._cont = SN_whileLoopMain_reentry0_cont0c_k + offsetof(whileLoopMain_reentry0_cont0_task, b_j);
    memReader_args3.mem = args.B;
    memReader_args3.idx = args.j;
    taskOutGlobal.write(memReader_args3);
  }
  else
  {
    MEM_OUT(mem, args.count + sizeof(int32_t), int32_t, 1);
  }
}
