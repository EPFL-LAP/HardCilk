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
//   H_NULL   (0)        empty slot, host skips.
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
#define WATCHER_BURST_LEN 16
#define WATCHER_FLUSH_TIMEOUT 16

#define H_NULL 0
#define H_STATUS 1
#define H_BW_R 2 // 2,3,4
#define H_BW_W 5 // 5,6,7
#define H_BW_ADDR 8

// --------------------------------------------------------
// WATCHER KERNEL
// --------------------------------------------------------

void watcher_runner(
    hls::stream<ap_uint<256>> &write_queue,
    ap_uint<2> cont0_status_in[N_whileLoopMain_reentry0_cont0],
    ap_uint<2> cont0_status_out[N_whileLoopMain_reentry0_cont0],
    ap_uint<2> memReader_status_in[N_memReader],
    ap_uint<2> memReader_status_out[N_memReader],
    ap_uint<2> reentry0_status_in[N_whileLoopMain_reentry0],
    ap_uint<2> reentry0_status_out[N_whileLoopMain_reentry0],
    ap_uint<8> bw_wbytes[MAX_HBM_PORTS],
    ap_uint<16> bw_rbytes[MAX_HBM_PORTS],
    ap_uint<20> bw_awaddr[MAX_HBM_PORTS],
    ap_uint<20> bw_araddr[MAX_HBM_PORTS],
    bool start_gate);

struct watcher_burst_cmd
{
  uint64_t addr;
  ap_uint<5> count;
};

// Helper: write a burst command to the selected channel's cmd queue.
static void watcher_route_cmd(
    ap_uint<3> port, watcher_burst_cmd cmd,
    hls::stream<watcher_burst_cmd> &cq0, hls::stream<watcher_burst_cmd> &cq1,
    hls::stream<watcher_burst_cmd> &cq2, hls::stream<watcher_burst_cmd> &cq3,
    hls::stream<watcher_burst_cmd> &cq4, hls::stream<watcher_burst_cmd> &cq5,
    hls::stream<watcher_burst_cmd> &cq6, hls::stream<watcher_burst_cmd> &cq7)
{
  switch (port)
  {
  case 0: cq0.write(cmd); break;
  case 1: cq1.write(cmd); break;
  case 2: cq2.write(cmd); break;
  case 3: cq3.write(cmd); break;
  case 4: cq4.write(cmd); break;
  case 5: cq5.write(cmd); break;
  case 6: cq6.write(cmd); break;
  default: cq7.write(cmd); break;
  }
}

// Distributor (telemetry fix #1): drains write_queue at II=1 and round-robins
// each WATCHER_BURST_LEN-beat burst across the 8 channels' data+cmd queues. The
// 8 channels are distinct AXI IDs to the SAME physical telemetry port; routing
// consecutive bursts to different channels lets several bursts be in flight at
// once, so the port's write-data channel stays saturated and the per-burst
// dispatch latency that throttled the old single serial writer is hidden. A
// global write_idx keeps the host readback stream contiguous. Drains at 1 beat/cycle,
// matching the sampler's peak, so write_queue never backs up -> no dropped
// frames. No telemetry is subsampled.
void watcher_distributor(
    hls::stream<ap_uint<256>> &write_queue,
    hls::stream<ap_uint<256>> &dq0, hls::stream<ap_uint<256>> &dq1,
    hls::stream<ap_uint<256>> &dq2, hls::stream<ap_uint<256>> &dq3,
    hls::stream<ap_uint<256>> &dq4, hls::stream<ap_uint<256>> &dq5,
    hls::stream<ap_uint<256>> &dq6, hls::stream<ap_uint<256>> &dq7,
    hls::stream<watcher_burst_cmd> &cq0, hls::stream<watcher_burst_cmd> &cq1,
    hls::stream<watcher_burst_cmd> &cq2, hls::stream<watcher_burst_cmd> &cq3,
    hls::stream<watcher_burst_cmd> &cq4, hls::stream<watcher_burst_cmd> &cq5,
    hls::stream<watcher_burst_cmd> &cq6, hls::stream<watcher_burst_cmd> &cq7,
    uint64_t start_addr)
{
  static ap_uint<3> port = 0;
  static ap_uint<5> burst_count = 0;
  static ap_uint<5> idle_count = 0;
  static uint64_t write_idx = 0;

  while (true)
  {
#pragma HLS pipeline II = 1
    ap_uint<256> beat;
    bool got_beat = write_queue.read_nb(beat);
    if (got_beat)
    {
      switch (port)
      {
      case 0: dq0.write(beat); break;
      case 1: dq1.write(beat); break;
      case 2: dq2.write(beat); break;
      case 3: dq3.write(beat); break;
      case 4: dq4.write(beat); break;
      case 5: dq5.write(beat); break;
      case 6: dq6.write(beat); break;
      default: dq7.write(beat); break;
      }
      ap_uint<5> next_count = burst_count + 1;
      if (next_count == WATCHER_BURST_LEN)
      {
        watcher_burst_cmd cmd;
        cmd.addr = (start_addr / 32) + write_idx;
        cmd.count = WATCHER_BURST_LEN;
        watcher_route_cmd(port, cmd, cq0, cq1, cq2, cq3, cq4, cq5, cq6, cq7);
        write_idx += WATCHER_BURST_LEN;
        burst_count = 0;
        port++;
      }
      else
      {
        burst_count = next_count;
      }
      idle_count = 0;
    }
    else if (burst_count > 0)
    {
      ap_uint<5> next_idle = idle_count + 1;
      if (next_idle >= WATCHER_FLUSH_TIMEOUT)
      {
        watcher_burst_cmd cmd;
        cmd.addr = (start_addr / 32) + write_idx;
        cmd.count = burst_count;
        watcher_route_cmd(port, cmd, cq0, cq1, cq2, cq3, cq4, cq5, cq6, cq7);
        write_idx += (uint64_t)burst_count;
        burst_count = 0;
        idle_count = 0;
        port++;
      }
      else
      {
        idle_count = next_idle;
      }
    }
  }
}

// One writer per channel; the 8 instances run concurrently in the dataflow
// region. Full queues still issue WATCHER_BURST_LEN-beat bursts. Idle-flushed
// partials issue only cmd.count beats, rather than padding the remaining slots
// into physical writes; otherwise the regular BW samples amplify into a nearly
// continuous telemetry write stream.
#define WATCHER_WRITER_FN(ID)                                                                  \
  static void watcher_writer_##ID(ap_uint<256> *mem,                                           \
                                  hls::stream<watcher_burst_cmd> &cmd_queue,                    \
                                  hls::stream<ap_uint<256>> &data_queue)                        \
  {                                                                                            \
    while (true)                                                                               \
    {                                                                                          \
      watcher_burst_cmd cmd = cmd_queue.read();                                                \
      for (int i = 0; i < WATCHER_BURST_LEN; i++)                                              \
      {                                                                                        \
        _Pragma("HLS pipeline II = 1")                                                         \
        if (i < cmd.count)                                                                     \
        {                                                                                      \
          ap_uint<256> beat = data_queue.read();                                               \
          mem[cmd.addr + i] = beat;                                                            \
        }                                                                                      \
      }                                                                                        \
    }                                                                                          \
  }

WATCHER_WRITER_FN(0)
WATCHER_WRITER_FN(1)
WATCHER_WRITER_FN(2)
WATCHER_WRITER_FN(3)
WATCHER_WRITER_FN(4)
WATCHER_WRITER_FN(5)
WATCHER_WRITER_FN(6)
WATCHER_WRITER_FN(7)

void watcher(
    ap_uint<256> *mem_0, ap_uint<256> *mem_1, ap_uint<256> *mem_2, ap_uint<256> *mem_3, ap_uint<256> *mem_4, ap_uint<256> *mem_5, ap_uint<256> *mem_6, ap_uint<256> *mem_7,
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
#pragma HLS INTERFACE mode = m_axi port = mem_0 bundle = gmem channel = 0 offset = direct latency = 48 num_write_outstanding = 16 max_write_burst_length = 16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_1 bundle = gmem channel = 1 offset = direct latency = 48 num_write_outstanding = 16 max_write_burst_length = 16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_2 bundle = gmem channel = 2 offset = direct latency = 48 num_write_outstanding = 16 max_write_burst_length = 16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_3 bundle = gmem channel = 3 offset = direct latency = 48 num_write_outstanding = 16 max_write_burst_length = 16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_4 bundle = gmem channel = 4 offset = direct latency = 48 num_write_outstanding = 16 max_write_burst_length = 16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_5 bundle = gmem channel = 5 offset = direct latency = 48 num_write_outstanding = 16 max_write_burst_length = 16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_6 bundle = gmem channel = 6 offset = direct latency = 48 num_write_outstanding = 16 max_write_burst_length = 16 max_widen_bitwidth = 256
#pragma HLS INTERFACE mode = m_axi port = mem_7 bundle = gmem channel = 7 offset = direct latency = 48 num_write_outstanding = 16 max_write_burst_length = 16 max_widen_bitwidth = 256
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

#pragma HLS dataflow
  hls::stream<ap_uint<256>> write_queue;
#pragma HLS stream variable = write_queue depth = 256
  // Per-channel data + cmd queues; the 8 writers drain these concurrently.
  hls::stream<ap_uint<256>> dq0, dq1, dq2, dq3, dq4, dq5, dq6, dq7;
#pragma HLS stream variable = dq0 depth = 64
#pragma HLS stream variable = dq1 depth = 64
#pragma HLS stream variable = dq2 depth = 64
#pragma HLS stream variable = dq3 depth = 64
#pragma HLS stream variable = dq4 depth = 64
#pragma HLS stream variable = dq5 depth = 64
#pragma HLS stream variable = dq6 depth = 64
#pragma HLS stream variable = dq7 depth = 64
  hls::stream<watcher_burst_cmd> cq0, cq1, cq2, cq3, cq4, cq5, cq6, cq7;
#pragma HLS stream variable = cq0 depth = 8
#pragma HLS stream variable = cq1 depth = 8
#pragma HLS stream variable = cq2 depth = 8
#pragma HLS stream variable = cq3 depth = 8
#pragma HLS stream variable = cq4 depth = 8
#pragma HLS stream variable = cq5 depth = 8
#pragma HLS stream variable = cq6 depth = 8
#pragma HLS stream variable = cq7 depth = 8

  watcher_runner(write_queue, cont0_status_in, cont0_status_out, memReader_status_in, memReader_status_out, reentry0_status_in, reentry0_status_out, bw_wbytes, bw_rbytes, bw_awaddr, bw_araddr, start_gate);
  // Do not pass start_gate into this dataflow process: HLS materializes scalar
  // arguments as one-shot FIFOs, so the distributor could latch the reset-time 0 forever.
  watcher_distributor(write_queue, dq0, dq1, dq2, dq3, dq4, dq5, dq6, dq7,
                      cq0, cq1, cq2, cq3, cq4, cq5, cq6, cq7, start_addr);
  watcher_writer_0(mem_0, cq0, dq0);
  watcher_writer_1(mem_1, cq1, dq1);
  watcher_writer_2(mem_2, cq2, dq2);
  watcher_writer_3(mem_3, cq3, dq3);
  watcher_writer_4(mem_4, cq4, dq4);
  watcher_writer_5(mem_5, cq5, dq5);
  watcher_writer_6(mem_6, cq6, dq6);
  watcher_writer_7(mem_7, cq7, dq7);
}

void watcher_runner(
    hls::stream<ap_uint<256>> &write_queue, // 256-bit AXI beat = two 128-bit bundle slots {slot1,slot0}
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
    bool start_gate)
{

  // -------- persistent state (reset-initialized to 0 / false) --------
  static bool running = false;       // start-gate latch
  static uint64_t cycle_count = 0;   // cycles since the start gate opened
  static ap_uint<48> lastStatus = 0; // last 48 status bits (change detection)
  static ap_uint<32> read_acc[MAX_HBM_PORTS] = {0};
  static ap_uint<32> write_acc[MAX_HBM_PORTS] = {0};
#pragma HLS ARRAY_PARTITION variable = read_acc complete dim = 1
#pragma HLS ARRAY_PARTITION variable = write_acc complete dim = 1
  static ap_uint<12> win_cnt = 0;     // window cycle counter (0..WINDOW-1)
  static ap_uint<5> addr_rot = 0;     // rotating port for the address sample
  static ap_uint<128> bwq[BWQ_DEPTH]; // pending bundle queue (empty each window)
#pragma HLS ARRAY_PARTITION variable = bwq complete dim = 1
  static ap_uint<4> bwq_head = 0;
  static ap_uint<4> bwq_count = 0;

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
        cycle_count = 0;
        win_cnt = 0;
        bwq_head = 0;
        bwq_count = 0;
        addr_rot = 0;
        lastStatus = 0;
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
      ap_uint<32> r_old = read_acc[p];
      ap_uint<32> w_old = write_acc[p];
      ap_uint<16> r_inc = rbytes_v[p];
      ap_uint<8> w_inc = wbytes_v[p];
      // average over the window uses the OLD accumulator (before this cycle)
      ap_uint<32> r_avg = r_old >> WIN_SHIFT;
      ap_uint<32> w_avg = w_old >> WIN_SHIFT;
      avg_r[p] = (r_avg > 255) ? (ap_uint<8>)255 : (ap_uint<8>)r_avg;
      avg_w[p] = (w_avg > 255) ? (ap_uint<8>)255 : (ap_uint<8>)w_avg;
      // reset to this cycle's bytes at the window boundary, else accumulate
      read_acc[p] = window ? (ap_uint<32>)r_inc : (ap_uint<32>)(r_old + r_inc);
      write_acc[p] = window ? (ap_uint<32>)w_inc : (ap_uint<32>)(w_old + w_inc);
    }
    win_cnt = window ? (ap_uint<12>)0 : (ap_uint<12>)(win_cnt + 1);

    // ---- window boundary: build the 7 BW bundles into the (empty) queue ----
    // The previous window's <=7 bundles flushed within <=7 cycles, far inside
    // the 4096-cycle window, so the queue is always empty here.
    if (window)
    {
      bool any_bw = false;
      for (int p = 0; p < MAX_HBM_PORTS; p++)
      {
#pragma HLS unroll
        if (avg_r[p] != 0 || avg_w[p] != 0)
          any_bw = true;
      }
      if (any_bw)
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
      // [124:53] = the window-END cycle (this 128-cycle window covers cycles
      // [cycle-127 .. cycle]); lets the viewer time-anchor each BW set directly
      // instead of inferring it (robust to skipped idle windows / drops).
      ab(124, 53) = (ap_uint<72>)cycle_count;
      bwq[2 * N_BW_SUB] = ab;
      bwq_head = 0;
      bwq_count = 2 * N_BW_SUB + 1; // 7
      addr_rot = (addr_rot + 1 >= MAX_HBM_PORTS) ? (ap_uint<5>)0 : (ap_uint<5>)(addr_rot + 1);
      }
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
      write_queue.write(beat);
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
