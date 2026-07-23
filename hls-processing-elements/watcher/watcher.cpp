#include "hls_stream.h"
#include "hls_burst_maxi.h"
#include <ap_int.h>
#include <stdint.h>

// Twelve generic four-bit physical taps. JSON/generator wiring assigns all semantics.
#define MAX_STATUS_SLOTS 22

// ====================================================================
// Telemetry bundle format -- BIT-PACKED 128-bit words (NOT natural layout)
// ====================================================================
// One 256-bit AXI beat carries TWO 128-bit bundles: beat = {slot1, slot0}.
// The runner emits at most one beat/cycle into the distributor. Current watcher
// builds split those beats across two physical HBM write ports; the host merges the
// two raw regions back into one ordered stream before writing the .bin. Each
// 128-bit bundle is [7:0] = header (type):
//   H_NULL   (0)        empty slot, host skips.
//   H_STATUS (1)        [95:8]  = 88 generic status bits (22 slots x 4)
//                       [127:96]= 32-bit cycle_count (since the start gate)
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
// 64 beats = 2 KiB. A command can become at most two physical AXI bursts if its
// starting address lies in the second half of a 4 KiB page.
#define WATCHER_BURST_LEN 64
#define WATCHER_FLUSH_TIMEOUT 16
// Keep exactly one AXI ID on each of the two physical write ports. This is the
// configuration used by hardware; throughput comes from the two physical buses,
// not from multiplexing several HLS writers onto either bus.
#ifndef WATCHER_USE_MULTICHANNEL_WRITERS
#define WATCHER_USE_MULTICHANNEL_WRITERS 0
#endif
// Physical port B's telemetry region sits exactly 4 GiB above port A's so the two
// never overlap in the 8 GB HBM space (different banks). Addresses are in 256-bit
// (32-byte) beat units, so 4 GiB = 2^32 bytes / 32 bytes/beat = 2^27 beats.
#define FOUR_GB_BEATS 134217728ULL

#define H_NULL 0
#define H_STATUS 1
#define H_BW_R 2 // 2,3,4
#define H_BW_W 5 // 5,6,7
#define H_BW_ADDR 8

// --------------------------------------------------------
// WATCHER KERNEL
// --------------------------------------------------------

// Keep each four-bit physical status pin behind its own tiny stream tap. Aggregate
// reads can be hoisted by HLS; per-pin taps preserve live II=1 sampling.
void watcher_status4_tap(
    ap_uint<4> &status,
    hls::stream<ap_uint<4>> &status_stream);

void watcher_gate_tap(
    ap_uint<1> start_gate[1],
    hls::stream<ap_uint<1>> &gate_stream);

void watcher_runner(
    hls::stream<ap_uint<256>> &write_queue,
    hls::stream<ap_uint<4>> status_stream[MAX_STATUS_SLOTS],
    hls::stream<ap_uint<1>> &gate_stream,
    ap_uint<8> bw_wbytes[MAX_HBM_PORTS],
    ap_uint<16> bw_rbytes[MAX_HBM_PORTS],
    ap_uint<20> bw_awaddr[MAX_HBM_PORTS],
    ap_uint<20> bw_araddr[MAX_HBM_PORTS]);

struct watcher_burst_cmd
{
  uint64_t addr;
  ap_uint<8> count;
};

#if WATCHER_USE_MULTICHANNEL_WRITERS
// -----------------------------------------------------------------------------
// Previous implementation: 8 HLS writer channels per physical AXI master.
// Retained intact for A/B comparison; enable with
// -DWATCHER_USE_MULTICHANNEL_WRITERS=1.
// -----------------------------------------------------------------------------
// Helper: write a burst command to the selected channel's cmd queue.
// Channels 0..7 belong to physical port A (bundle gmem), 8..15 to port B (gmem1).
static void watcher_route_cmd(
    ap_uint<4> chan, watcher_burst_cmd cmd,
    hls::stream<watcher_burst_cmd> &cq0, hls::stream<watcher_burst_cmd> &cq1,
    hls::stream<watcher_burst_cmd> &cq2, hls::stream<watcher_burst_cmd> &cq3,
    hls::stream<watcher_burst_cmd> &cq4, hls::stream<watcher_burst_cmd> &cq5,
    hls::stream<watcher_burst_cmd> &cq6, hls::stream<watcher_burst_cmd> &cq7,
    hls::stream<watcher_burst_cmd> &cq8, hls::stream<watcher_burst_cmd> &cq9,
    hls::stream<watcher_burst_cmd> &cq10, hls::stream<watcher_burst_cmd> &cq11,
    hls::stream<watcher_burst_cmd> &cq12, hls::stream<watcher_burst_cmd> &cq13,
    hls::stream<watcher_burst_cmd> &cq14, hls::stream<watcher_burst_cmd> &cq15)
{
  switch (chan)
  {
  case 0: cq0.write(cmd); break;
  case 1: cq1.write(cmd); break;
  case 2: cq2.write(cmd); break;
  case 3: cq3.write(cmd); break;
  case 4: cq4.write(cmd); break;
  case 5: cq5.write(cmd); break;
  case 6: cq6.write(cmd); break;
  case 7: cq7.write(cmd); break;
  case 8: cq8.write(cmd); break;
  case 9: cq9.write(cmd); break;
  case 10: cq10.write(cmd); break;
  case 11: cq11.write(cmd); break;
  case 12: cq12.write(cmd); break;
  case 13: cq13.write(cmd); break;
  case 14: cq14.write(cmd); break;
  default: cq15.write(cmd); break;
  }
}

// Distributor: drains write_queue at II=1 and round-robins each
// WATCHER_BURST_LEN-beat burst across 16 channels' data+cmd queues. Channels
// 0..7 are AXI IDs on physical port A (bundle gmem), 8..15 on physical port B
// (bundle gmem1) -- two DISTINCT physical W buses. Bursts ALTERNATE ports every
// dispatch (A0,B0,A1,B1,...,A7,B7,...) so telemetry is split evenly across the
// two masters, doubling the aggregate drain to ~2 beats/cycle. That clears the
// single-master 1-beat/cycle ceiling that structurally dropped STATUS frames
// (one 256-bit master can't sustain the runner's 1-beat/cycle production peak).
// Within each port the 8 channels still overlap to hide the per-burst AW/restart
// bubble. Each port owns an independent beat cursor (write_idxA/write_idxB) into
// its own 4 GiB-apart HBM region, so the two regions never overlap and fill
// together. No telemetry is subsampled.
void watcher_distributor(
    hls::stream<ap_uint<256>> &write_queue,
    hls::stream<ap_uint<256>> &dq0, hls::stream<ap_uint<256>> &dq1,
    hls::stream<ap_uint<256>> &dq2, hls::stream<ap_uint<256>> &dq3,
    hls::stream<ap_uint<256>> &dq4, hls::stream<ap_uint<256>> &dq5,
    hls::stream<ap_uint<256>> &dq6, hls::stream<ap_uint<256>> &dq7,
    hls::stream<ap_uint<256>> &dq8, hls::stream<ap_uint<256>> &dq9,
    hls::stream<ap_uint<256>> &dq10, hls::stream<ap_uint<256>> &dq11,
    hls::stream<ap_uint<256>> &dq12, hls::stream<ap_uint<256>> &dq13,
    hls::stream<ap_uint<256>> &dq14, hls::stream<ap_uint<256>> &dq15,
    hls::stream<watcher_burst_cmd> &cq0, hls::stream<watcher_burst_cmd> &cq1,
    hls::stream<watcher_burst_cmd> &cq2, hls::stream<watcher_burst_cmd> &cq3,
    hls::stream<watcher_burst_cmd> &cq4, hls::stream<watcher_burst_cmd> &cq5,
    hls::stream<watcher_burst_cmd> &cq6, hls::stream<watcher_burst_cmd> &cq7,
    hls::stream<watcher_burst_cmd> &cq8, hls::stream<watcher_burst_cmd> &cq9,
    hls::stream<watcher_burst_cmd> &cq10, hls::stream<watcher_burst_cmd> &cq11,
    hls::stream<watcher_burst_cmd> &cq12, hls::stream<watcher_burst_cmd> &cq13,
    hls::stream<watcher_burst_cmd> &cq14, hls::stream<watcher_burst_cmd> &cq15,
    uint64_t start_addr)
{
  static ap_uint<4> seq = 0;         // dispatch counter; wraps 0..15
  static ap_uint<8> burst_count = 0;
  static ap_uint<5> idle_count = 0;  // only reaches WATCHER_FLUSH_TIMEOUT (16)
  static uint64_t write_idxA = 0;    // beat cursor within port A's region
  static uint64_t write_idxB = 0;    // beat cursor within port B's region

  const uint64_t baseA = start_addr / 32;
  const uint64_t baseB = start_addr / 32 + FOUR_GB_BEATS;

  while (true)
  {
#pragma HLS pipeline II = 1
    // Current target channel, stable across a whole burst (seq only changes at
    // dispatch). Derived from seq so it can never desync from the port used for
    // the address: seq&1 selects the port (0=A,1=B); seq>>1 is that port's
    // round-robin channel 0..7. chan = (port<<3) | rr => 0..7 for A, 8..15 for B.
    ap_uint<4> chan = ((seq & 1) << 3) | (seq >> 1);
    ap_uint<256> beat;
    bool got_beat = write_queue.read_nb(beat);
    if (got_beat)
    {
      switch (chan)
      {
      case 0: dq0.write(beat); break;
      case 1: dq1.write(beat); break;
      case 2: dq2.write(beat); break;
      case 3: dq3.write(beat); break;
      case 4: dq4.write(beat); break;
      case 5: dq5.write(beat); break;
      case 6: dq6.write(beat); break;
      case 7: dq7.write(beat); break;
      case 8: dq8.write(beat); break;
      case 9: dq9.write(beat); break;
      case 10: dq10.write(beat); break;
      case 11: dq11.write(beat); break;
      case 12: dq12.write(beat); break;
      case 13: dq13.write(beat); break;
      case 14: dq14.write(beat); break;
      default: dq15.write(beat); break;
      }
      ap_uint<8> next_count = burst_count + 1;
      if (next_count == WATCHER_BURST_LEN)
      {
        watcher_burst_cmd cmd;
        if ((seq & 1) == 0)
        {
          cmd.addr = baseA + write_idxA;
          write_idxA += WATCHER_BURST_LEN;
        }
        else
        {
          cmd.addr = baseB + write_idxB;
          write_idxB += WATCHER_BURST_LEN;
        }
        cmd.count = WATCHER_BURST_LEN;
        watcher_route_cmd(chan, cmd, cq0, cq1, cq2, cq3, cq4, cq5, cq6, cq7,
                          cq8, cq9, cq10, cq11, cq12, cq13, cq14, cq15);
        burst_count = 0;
        seq++;
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
        if ((seq & 1) == 0)
        {
          cmd.addr = baseA + write_idxA;
          write_idxA += (uint64_t)burst_count;
        }
        else
        {
          cmd.addr = baseB + write_idxB;
          write_idxB += (uint64_t)burst_count;
        }
        cmd.count = burst_count;
        watcher_route_cmd(chan, cmd, cq0, cq1, cq2, cq3, cq4, cq5, cq6, cq7,
                          cq8, cq9, cq10, cq11, cq12, cq13, cq14, cq15);
        burst_count = 0;
        idle_count = 0;
        seq++;
      }
      else
      {
        idle_count = next_idle;
      }
    }
  }
}

// Manual-burst writers issue request+data without waiting for B. A separate
// free-running dataflow process consumes one write response per request. The m_axi
// adapter therefore keeps accepting commands until its num_write_outstanding
// capacity is occupied, instead of serializing every command on HBM response
// latency. The cmd.count-bounded data loop emits partial bursts without padding.
#define WATCHER_WRITER_FN(ID)                                                                  \
  static void watcher_writer_##ID(hls::burst_maxi<ap_uint<256>> &mem,                          \
                                  hls::stream<watcher_burst_cmd> &cmd_queue,                    \
                                  hls::stream<ap_uint<256>> &data_queue)                        \
  {                                                                                            \
    while (true)                                                                               \
    {                                                                                          \
      watcher_burst_cmd cmd = cmd_queue.read();                                                \
      mem.write_request(cmd.addr, cmd.count);                                                   \
      for (int i = 0; i < (int)cmd.count; i++)                                                 \
      {                                                                                        \
        _Pragma("HLS loop_tripcount min = 1 max = 64")                                         \
        _Pragma("HLS pipeline II = 1")                                                         \
        ap_uint<256> beat = data_queue.read();                                                 \
        mem.write(beat);                                                                        \
      }                                                                                        \
    }                                                                                          \
  }                                                                                            \
                                                                                               \
  static void watcher_write_responses_##ID(hls::burst_maxi<ap_uint<256>> &mem)                 \
  {                                                                                            \
    /* Blocking is intentional: this actor owns only B and wakes per response. */              \
    while (true)                                                                               \
      mem.write_response();                                                                    \
  }

WATCHER_WRITER_FN(0)
WATCHER_WRITER_FN(1)
WATCHER_WRITER_FN(2)
WATCHER_WRITER_FN(3)
WATCHER_WRITER_FN(4)
WATCHER_WRITER_FN(5)
WATCHER_WRITER_FN(6)
WATCHER_WRITER_FN(7)
WATCHER_WRITER_FN(8)
WATCHER_WRITER_FN(9)
WATCHER_WRITER_FN(10)
WATCHER_WRITER_FN(11)
WATCHER_WRITER_FN(12)
WATCHER_WRITER_FN(13)
WATCHER_WRITER_FN(14)
WATCHER_WRITER_FN(15)
#endif // WATCHER_USE_MULTICHANNEL_WRITERS

#if !WATCHER_USE_MULTICHANNEL_WRITERS
// Split telemetry beats across the two physical ports, routing each beat to
// whichever port has room RIGHT NOW instead of a fixed A,B,A,B alternation.
//
// Why not strict alternation: the previous version blocked on the selected port's
// FIFO (`raw_a.write(beat)`), so while port A rode out a burst turnaround (real HBM
// AW latency on a short idle-flushed burst), the split stalled on the A-write EVEN
// THOUGH port B sat idle with empty FIFOs. During that window write_queue drained at
// ZERO, filling at the runner's 1-beat/cycle production peak. The two physical buses
// therefore delivered only ~1 port's worth of sustained drain (whichever port was
// mid-turnaround gated BOTH), leaving the runner on the same zero-margin knife-edge
// as the old single-port build -> write_queue eventually filled -> the status taps
// overflowed and dropped frames. hw_emu never exposed this because its HBM model
// grants AW/WREADY at ~zero latency, so the blocking write never stalled.
//
// Preserve the original splitter's one-beat elasticity in an explicit FIFO. Keeping
// that storage outside the routing loop prevents HLS from building a 256-bit
// loop-carried feedback mux while still allowing one beat to leave write_queue when
// both destinations are full.
static void watcher_split_prefetch(
    hls::stream<ap_uint<256>> &write_queue,
    hls::stream<ap_uint<256>> &split_skid)
{
  while (true)
  {
#pragma HLS pipeline II = 1
    split_skid.write(write_queue.read());
  }
}

// Capacity-aware placement. Check both destinations before consuming the explicit
// skid, then send to the preferred port or fall back to the other. When both raw
// FIFOs are full, the skid holds the in-flight beat exactly as the old local
// register did. `prefer_b` alternates on every successful placement, so under no
// backpressure this still stripes A,B,A,B and both 4 GiB regions fill together; the
// host reassembles global order by the cycle_count stamped in every bundle, so
// cross-port reordering under backpressure is harmless. This lets the two ports
// deliver their full aggregate drain, keeping write_queue ahead of production.
static void watcher_split_two_ports(
    hls::stream<ap_uint<256>> &split_skid,
    hls::stream<ap_uint<256>> &raw_a,
    hls::stream<ap_uint<256>> &raw_b)
{
  ap_uint<1> prefer_b = 0; // load-balance tie-break; flips on each placement

  while (true)
  {
#pragma HLS pipeline II = 1
    // Looking at full() before read_nb() removes the old 256-bit loop-carried
    // skid register and its feedback mux. A FIFO cannot become more full during
    // this actor's iteration, so the subsequent write to the chosen FIFO cannot
    // block after its capacity check succeeds.
    bool a_available = !raw_a.full();
    bool b_available = !raw_b.full();
    if (a_available || b_available)
    {
      ap_uint<256> beat;
      if (split_skid.read_nb(beat))
      {
        // Exact original policy: preferred port first, other port as fallback.
        bool use_b = b_available && (prefer_b || !a_available);
        if (use_b)
          raw_b.write(beat);
        else
          raw_a.write(beat);
        prefer_b = ~prefer_b;
      }
    }
  }
}

// Separate named functions force two independent dataflow processes and let each
// consume start_addr directly (passing arithmetic expressions between dataflow
// processes makes HLS synthesize unnecessary one-shot scalar FIFOs).
#define WATCHER_BURST_BUILDER_FN(NAME, REGION_OFFSET)                                      \
  static void watcher_build_bursts_##NAME(                                                 \
      hls::stream<ap_uint<256>> &raw,                                                       \
      hls::stream<ap_uint<256>> &data,                                                      \
      hls::stream<watcher_burst_cmd> &cmd_queue,                                            \
      uint64_t start_addr)                                                                  \
  {                                                                                         \
    ap_uint<8> burst_count = 0;                                                             \
    ap_uint<5> idle_count = 0;                                                              \
    uint64_t write_idx = 0;                                                                 \
    const uint64_t base = start_addr / 32 + (REGION_OFFSET);                               \
    while (true)                                                                            \
    {                                                                                       \
      _Pragma("HLS pipeline II = 1")                                                       \
      ap_uint<256> beat;                                                                    \
      bool got_beat = raw.read_nb(beat);                                                    \
      if (got_beat)                                                                         \
      {                                                                                     \
        data.write(beat);                                                                   \
        ap_uint<8> next_count = burst_count + 1;                                            \
        if (next_count == WATCHER_BURST_LEN)                                                \
        {                                                                                   \
          watcher_burst_cmd cmd;                                                            \
          cmd.count = WATCHER_BURST_LEN;                                                    \
          cmd.addr = base + write_idx;                                                      \
          write_idx += WATCHER_BURST_LEN;                                                   \
          cmd_queue.write(cmd);                                                             \
          burst_count = 0;                                                                  \
        }                                                                                   \
        else                                                                                \
          burst_count = next_count;                                                         \
        idle_count = 0;                                                                     \
      }                                                                                     \
      else if (burst_count > 0)                                                             \
      {                                                                                     \
        ap_uint<5> next_idle = idle_count + 1;                                              \
        if (next_idle >= WATCHER_FLUSH_TIMEOUT)                                             \
        {                                                                                   \
          watcher_burst_cmd cmd;                                                            \
          cmd.count = burst_count;                                                          \
          cmd.addr = base + write_idx;                                                      \
          write_idx += (uint64_t)burst_count;                                               \
          cmd_queue.write(cmd);                                                             \
          burst_count = 0;                                                                  \
          idle_count = 0;                                                                   \
        }                                                                                   \
        else                                                                                \
          idle_count = next_idle;                                                           \
      }                                                                                     \
    }                                                                                       \
  }

WATCHER_BURST_BUILDER_FN(port_a, 0)
WATCHER_BURST_BUILDER_FN(port_b, FOUR_GB_BEATS)

#define WATCHER_SINGLE_WRITER_FN(NAME)                                                     \
  static void watcher_writer_##NAME(hls::burst_maxi<ap_uint<256>> &mem,                   \
                                    hls::stream<watcher_burst_cmd> &cmd_queue,             \
                                    hls::stream<ap_uint<256>> &data_queue)                 \
  {                                                                                        \
    while (true)                                                                           \
    {                                                                                      \
      watcher_burst_cmd cmd = cmd_queue.read();                                            \
      mem.write_request(cmd.addr, cmd.count);                                               \
      for (int i = 0; i < (int)cmd.count; i++)                                             \
      {                                                                                    \
        _Pragma("HLS loop_tripcount min = 1 max = 64")                                   \
        _Pragma("HLS pipeline II = 1")                                                    \
        ap_uint<256> beat = data_queue.read();                                             \
        mem.write(beat);                                                                    \
      }                                                                                    \
    }                                                                                      \
  }                                                                                        \
                                                                                           \
  static void watcher_write_responses_##NAME(hls::burst_maxi<ap_uint<256>> &mem)          \
  {                                                                                        \
    /* Blocking is intentional: this actor owns only B and wakes per response. */          \
    while (true)                                                                           \
      mem.write_response();                                                                \
  }

WATCHER_SINGLE_WRITER_FN(port_a)
WATCHER_SINGLE_WRITER_FN(port_b)
#endif // !WATCHER_USE_MULTICHANNEL_WRITERS

void watcher(
    hls::burst_maxi<ap_uint<256>> mem_0,
#if WATCHER_USE_MULTICHANNEL_WRITERS
    hls::burst_maxi<ap_uint<256>> mem_1, hls::burst_maxi<ap_uint<256>> mem_2,
    hls::burst_maxi<ap_uint<256>> mem_3, hls::burst_maxi<ap_uint<256>> mem_4,
    hls::burst_maxi<ap_uint<256>> mem_5, hls::burst_maxi<ap_uint<256>> mem_6,
    hls::burst_maxi<ap_uint<256>> mem_7,
#endif
    hls::burst_maxi<ap_uint<256>> mem_8,
#if WATCHER_USE_MULTICHANNEL_WRITERS
    hls::burst_maxi<ap_uint<256>> mem_9, hls::burst_maxi<ap_uint<256>> mem_10,
    hls::burst_maxi<ap_uint<256>> mem_11, hls::burst_maxi<ap_uint<256>> mem_12,
    hls::burst_maxi<ap_uint<256>> mem_13, hls::burst_maxi<ap_uint<256>> mem_14,
    hls::burst_maxi<ap_uint<256>> mem_15,
#endif
    uint64_t start_addr, // byte offset provided by the host (port A base; port B = +4 GiB)
    // Twelve generic four-bit status taps. JSON/generator wiring assigns their
    // meaning; the HLS only snapshots and packs them.
    ap_uint<4> status[MAX_STATUS_SLOTS],
    // --- per-HBM-port bandwidth taps (already byte-accurate, computed in Chisel) ---
    ap_uint<8> bw_wbytes[MAX_HBM_PORTS],  // write bytes this cycle (PopCount WSTRB)
    ap_uint<16> bw_rbytes[MAX_HBM_PORTS], // read  bytes this cycle ((ARLEN+1)<<ARSIZE)
    ap_uint<20> bw_awaddr[MAX_HBM_PORTS], // most-recent AW top-20 addr bits (tapped, future)
    ap_uint<20> bw_araddr[MAX_HBM_PORTS], // most-recent AR top-20 addr bits (tapped, future)
    ap_uint<1> start_gate[1])             // first-task-dispatch from the spawn scheduler
{
  // Active build: one channel on each AXI4 master. The old channel declarations
  // and pragmas remain in the guarded blocks so WATCHER_USE_MULTICHANNEL_WRITERS=1
  // restores the previous eight channels per master.
#pragma HLS INTERFACE mode = m_axi port = mem_0 bundle = gmem channel = 0 offset = direct num_write_outstanding = 16 max_write_burst_length = 64
#if WATCHER_USE_MULTICHANNEL_WRITERS
#pragma HLS INTERFACE mode = m_axi port = mem_1 bundle = gmem channel = 1 offset = direct num_write_outstanding = 16 max_write_burst_length = 64
#pragma HLS INTERFACE mode = m_axi port = mem_2 bundle = gmem channel = 2 offset = direct num_write_outstanding = 16 max_write_burst_length = 64
#pragma HLS INTERFACE mode = m_axi port = mem_3 bundle = gmem channel = 3 offset = direct num_write_outstanding = 16 max_write_burst_length = 64
#pragma HLS INTERFACE mode = m_axi port = mem_4 bundle = gmem channel = 4 offset = direct num_write_outstanding = 16 max_write_burst_length = 64
#pragma HLS INTERFACE mode = m_axi port = mem_5 bundle = gmem channel = 5 offset = direct num_write_outstanding = 16 max_write_burst_length = 64
#pragma HLS INTERFACE mode = m_axi port = mem_6 bundle = gmem channel = 6 offset = direct num_write_outstanding = 16 max_write_burst_length = 64
#pragma HLS INTERFACE mode = m_axi port = mem_7 bundle = gmem channel = 7 offset = direct num_write_outstanding = 16 max_write_burst_length = 64
#endif
#pragma HLS INTERFACE mode = m_axi port = mem_8 bundle = gmem1 channel = 0 offset = direct num_write_outstanding = 16 max_write_burst_length = 64
#if WATCHER_USE_MULTICHANNEL_WRITERS
#pragma HLS INTERFACE mode = m_axi port = mem_9 bundle = gmem1 channel = 1 offset = direct num_write_outstanding = 16 max_write_burst_length = 64
#pragma HLS INTERFACE mode = m_axi port = mem_10 bundle = gmem1 channel = 2 offset = direct num_write_outstanding = 16 max_write_burst_length = 64
#pragma HLS INTERFACE mode = m_axi port = mem_11 bundle = gmem1 channel = 3 offset = direct num_write_outstanding = 16 max_write_burst_length = 64
#pragma HLS INTERFACE mode = m_axi port = mem_12 bundle = gmem1 channel = 4 offset = direct num_write_outstanding = 16 max_write_burst_length = 64
#pragma HLS INTERFACE mode = m_axi port = mem_13 bundle = gmem1 channel = 5 offset = direct num_write_outstanding = 16 max_write_burst_length = 64
#pragma HLS INTERFACE mode = m_axi port = mem_14 bundle = gmem1 channel = 6 offset = direct num_write_outstanding = 16 max_write_burst_length = 64
#pragma HLS INTERFACE mode = m_axi port = mem_15 bundle = gmem1 channel = 7 offset = direct num_write_outstanding = 16 max_write_burst_length = 64
#endif
#pragma HLS INTERFACE mode = ap_none port = start_addr
#pragma HLS INTERFACE ap_ctrl_none port = return

// Status: one discrete four-bit ap_none pin per physical slot (status_0..21).
#pragma HLS ARRAY_PARTITION variable = status complete dim = 1
#pragma HLS INTERFACE mode = ap_none port = status

// Bandwidth + address taps: one discrete ap_none input pin per port
#pragma HLS ARRAY_PARTITION variable = bw_wbytes complete dim = 1
#pragma HLS INTERFACE mode = ap_none port = bw_wbytes
#pragma HLS ARRAY_PARTITION variable = bw_rbytes complete dim = 1
#pragma HLS INTERFACE mode = ap_none port = bw_rbytes
#pragma HLS ARRAY_PARTITION variable = bw_awaddr complete dim = 1
#pragma HLS INTERFACE mode = ap_none port = bw_awaddr
#pragma HLS ARRAY_PARTITION variable = bw_araddr complete dim = 1
#pragma HLS INTERFACE mode = ap_none port = bw_araddr
#pragma HLS ARRAY_PARTITION variable = start_gate complete dim = 1
#pragma HLS INTERFACE mode = ap_none port = start_gate

#pragma HLS dataflow
  hls::stream<ap_uint<4>> status_stream[MAX_STATUS_SLOTS];
// Burst-absorbing depth. Root cause of dropped STATUS frames is NOT write
// bandwidth (measured: writers idle ~98%, HBM never backpressures) but shallow
// FIFOs that cannot ride out correlated status-change bursts (runner emits at
// its 1-beat/cycle ceiling for ~110-cyc stretches while avg demand is ~0.15).
// These streams are 2-bit, so deep is nearly free; sized to hold the worst
// observed burst so a tap never has to drop when the runner briefly stalls.
#pragma HLS stream variable = status_stream depth = 256
#pragma HLS ARRAY_PARTITION variable = status_stream complete dim = 1
  hls::stream<ap_uint<1>> gate_stream;
#pragma HLS stream variable = gate_stream depth = 32
  hls::stream<ap_uint<256>> write_queue;
// Runner's escape valve. Two distinct physical masters (`gmem` and `gmem1`) provide
// enough aggregate writer capacity to stay ahead of the runner's 1-beat/cycle
// production ceiling. This queue absorbs short bursts and per-port AW/restart
// bubbles so the runner does not stall and lose a status transition.
#pragma HLS stream variable = write_queue depth = 1024
#if WATCHER_USE_MULTICHANNEL_WRITERS
  // Per-channel data + cmd queues; the 16 writers drain these concurrently.
  hls::stream<ap_uint<256>> dq0, dq1, dq2, dq3, dq4, dq5, dq6, dq7;
  hls::stream<ap_uint<256>> dq8, dq9, dq10, dq11, dq12, dq13, dq14, dq15;
#pragma HLS stream variable = dq0 depth = 128
#pragma HLS stream variable = dq1 depth = 128
#pragma HLS stream variable = dq2 depth = 128
#pragma HLS stream variable = dq3 depth = 128
#pragma HLS stream variable = dq4 depth = 128
#pragma HLS stream variable = dq5 depth = 128
#pragma HLS stream variable = dq6 depth = 128
#pragma HLS stream variable = dq7 depth = 128
#pragma HLS stream variable = dq8 depth = 128
#pragma HLS stream variable = dq9 depth = 128
#pragma HLS stream variable = dq10 depth = 128
#pragma HLS stream variable = dq11 depth = 128
#pragma HLS stream variable = dq12 depth = 128
#pragma HLS stream variable = dq13 depth = 128
#pragma HLS stream variable = dq14 depth = 128
#pragma HLS stream variable = dq15 depth = 128
  hls::stream<watcher_burst_cmd> cq0, cq1, cq2, cq3, cq4, cq5, cq6, cq7;
  hls::stream<watcher_burst_cmd> cq8, cq9, cq10, cq11, cq12, cq13, cq14, cq15;
#pragma HLS stream variable = cq0 depth = 8
#pragma HLS stream variable = cq1 depth = 8
#pragma HLS stream variable = cq2 depth = 8
#pragma HLS stream variable = cq3 depth = 8
#pragma HLS stream variable = cq4 depth = 8
#pragma HLS stream variable = cq5 depth = 8
#pragma HLS stream variable = cq6 depth = 8
#pragma HLS stream variable = cq7 depth = 8
#pragma HLS stream variable = cq8 depth = 8
#pragma HLS stream variable = cq9 depth = 8
#pragma HLS stream variable = cq10 depth = 8
#pragma HLS stream variable = cq11 depth = 8
#pragma HLS stream variable = cq12 depth = 8
#pragma HLS stream variable = cq13 depth = 8
#pragma HLS stream variable = cq14 depth = 8
#pragma HLS stream variable = cq15 depth = 8
#else
  hls::stream<ap_uint<256>> split_skid;
// MUST be >1. At depth 1 this FIFO ping-pongs: prefetch writes, the FIFO is full, the
// registered full_n stalls prefetch for a cycle, two_ports reads, empty, repeat -- 2 cycles
// per beat. That capped the WHOLE telemetry drain at exactly 0.500 beat/cycle, upstream of
// both HBM ports, against the runner's 1 beat/cycle production peak -> write_queue filled ->
// the runner blocked on write_queue.write() -> it stopped draining the status taps -> the taps
// (which sample their pin LIVE, and so cannot queue a transition they never sampled) dropped
// STATUS frames. Measured on the 100x6000 hw_emu VCD, congested window (27804 cyc):
// skid_full_n high 50.00% (prefetch really was backpressured -- not an II=2 prefetch),
// raw_a/raw_b full_n high 100.00% (the two ports NEVER filled: the sink was starving, not
// backpressuring), each port only 25% utilised while frames were being lost.
// Both csynth II reports say II=1 for prefetch and two_ports IN ISOLATION -- the 0.5 is the
// depth-1 handshake INTERACTION and no per-actor II report can show it. Do not trust them here.
#pragma HLS stream variable = split_skid depth = 8
  hls::stream<ap_uint<256>> raw_a, raw_b;
#pragma HLS stream variable = raw_a depth = 128
#pragma HLS stream variable = raw_b depth = 128
  // One data queue and one command queue per physical writer.
  hls::stream<ap_uint<256>> data_a, data_b;
#pragma HLS stream variable = data_a depth = 128
#pragma HLS stream variable = data_b depth = 128
  hls::stream<watcher_burst_cmd> cmd_a, cmd_b;
#pragma HLS stream variable = cmd_a depth = 8
#pragma HLS stream variable = cmd_b depth = 8
#endif

  for (int i = 0; i < MAX_STATUS_SLOTS; i++)
  {
#pragma HLS unroll
    watcher_status4_tap(status[i], status_stream[i]);
  }
  watcher_gate_tap(start_gate, gate_stream);
  watcher_runner(write_queue, status_stream,
                 gate_stream, bw_wbytes, bw_rbytes, bw_awaddr, bw_araddr);
  // Do not pass start_gate into this dataflow process: HLS materializes scalar
  // arguments as one-shot FIFOs, so the distributor could latch the reset-time 0 forever.
#if WATCHER_USE_MULTICHANNEL_WRITERS
  watcher_distributor(write_queue,
                      dq0, dq1, dq2, dq3, dq4, dq5, dq6, dq7,
                      dq8, dq9, dq10, dq11, dq12, dq13, dq14, dq15,
                      cq0, cq1, cq2, cq3, cq4, cq5, cq6, cq7,
                      cq8, cq9, cq10, cq11, cq12, cq13, cq14, cq15, start_addr);
  watcher_writer_0(mem_0, cq0, dq0);
  watcher_writer_1(mem_1, cq1, dq1);
  watcher_writer_2(mem_2, cq2, dq2);
  watcher_writer_3(mem_3, cq3, dq3);
  watcher_writer_4(mem_4, cq4, dq4);
  watcher_writer_5(mem_5, cq5, dq5);
  watcher_writer_6(mem_6, cq6, dq6);
  watcher_writer_7(mem_7, cq7, dq7);
  watcher_writer_8(mem_8, cq8, dq8);
  watcher_writer_9(mem_9, cq9, dq9);
  watcher_writer_10(mem_10, cq10, dq10);
  watcher_writer_11(mem_11, cq11, dq11);
  watcher_writer_12(mem_12, cq12, dq12);
  watcher_writer_13(mem_13, cq13, dq13);
  watcher_writer_14(mem_14, cq14, dq14);
  watcher_writer_15(mem_15, cq15, dq15);
  watcher_write_responses_0(mem_0);
  watcher_write_responses_1(mem_1);
  watcher_write_responses_2(mem_2);
  watcher_write_responses_3(mem_3);
  watcher_write_responses_4(mem_4);
  watcher_write_responses_5(mem_5);
  watcher_write_responses_6(mem_6);
  watcher_write_responses_7(mem_7);
  watcher_write_responses_8(mem_8);
  watcher_write_responses_9(mem_9);
  watcher_write_responses_10(mem_10);
  watcher_write_responses_11(mem_11);
  watcher_write_responses_12(mem_12);
  watcher_write_responses_13(mem_13);
  watcher_write_responses_14(mem_14);
  watcher_write_responses_15(mem_15);
#else
  watcher_split_prefetch(write_queue, split_skid);
  watcher_split_two_ports(split_skid, raw_a, raw_b);
  watcher_build_bursts_port_a(raw_a, data_a, cmd_a, start_addr);
  watcher_build_bursts_port_b(raw_b, data_b, cmd_b, start_addr);
  watcher_writer_port_a(mem_0, cmd_a, data_a);
  watcher_writer_port_b(mem_8, cmd_b, data_b);
  watcher_write_responses_port_a(mem_0);
  watcher_write_responses_port_b(mem_8);
#endif
}

void watcher_gate_tap(
    ap_uint<1> start_gate[1],
    hls::stream<ap_uint<1>> &gate_stream)
{
  while (true)
  {
#pragma HLS pipeline II = 1
    volatile ap_uint<1> *gate_v = start_gate;
    ap_uint<1> gate_raw = gate_v[0];
    gate_stream.write(gate_raw);
  }
}

void watcher_status4_tap(
    ap_uint<4> &status,
    hls::stream<ap_uint<4>> &status_stream)
{
  while (true)
  {
#pragma HLS pipeline II = 1
    volatile ap_uint<4> *status_v = &status;
    ap_uint<4> raw = *status_v;
    status_stream.write(raw);
  }
}

void watcher_runner(
    hls::stream<ap_uint<256>> &write_queue, // 256-bit AXI beat = two 128-bit bundle slots {slot1,slot0}
    hls::stream<ap_uint<4>> status_stream[MAX_STATUS_SLOTS],
    hls::stream<ap_uint<1>> &gate_stream,
    // --- per-HBM-port bandwidth taps (already byte-accurate, computed in Chisel) ---
    ap_uint<8> bw_wbytes[MAX_HBM_PORTS],  // write bytes this cycle (PopCount WSTRB)
    ap_uint<16> bw_rbytes[MAX_HBM_PORTS], // read  bytes this cycle ((ARLEN+1)<<ARSIZE)
    ap_uint<20> bw_awaddr[MAX_HBM_PORTS], // most-recent AW top-20 addr bits (tapped, future)
    ap_uint<20> bw_araddr[MAX_HBM_PORTS]) // most-recent AR top-20 addr bits (tapped, future)
{

  // -------- persistent state (reset-initialized to 0 / false) --------
  static ap_uint<1> started_latched = 0; // start-gate latch
  static ap_uint<32> cycle_count = 0; // cycles since the start gate opened
  static ap_uint<88> lastStatus = 0;  // last 88 status bits (change detection)
  static ap_uint<32> read_acc[MAX_HBM_PORTS] = {0};
  static ap_uint<32> write_acc[MAX_HBM_PORTS] = {0};
#pragma HLS ARRAY_PARTITION variable = read_acc complete dim = 1
#pragma HLS ARRAY_PARTITION variable = write_acc complete dim = 1
  static ap_uint<12> win_cnt = 0;     // window cycle counter (0..WINDOW-1)
  static ap_uint<5> addr_rot = 0;     // rotating port for the address sample
  static ap_uint<128> bwq[BWQ_DEPTH]; // pending bundle queue (empty each window)
#pragma HLS ARRAY_PARTITION variable = bwq complete dim = 1
  static ap_uint<4> bwq_head = 0;
  // Low contiguous 1s represent queued bundles. This is equivalent to a count,
  // but consumption is a constant right shift instead of a compare/subtract
  // recurrence on the runner's critical path.
  static ap_uint<BWQ_DEPTH> bwq_valid = 0;

  while (true)
  {
#pragma HLS pipeline II = 1

    // ---- BW volatile views: fresh read every iteration (avoids HLS hoisting) ----
    volatile ap_uint<8> *wbytes_v = bw_wbytes;
    volatile ap_uint<16> *rbytes_v = bw_rbytes;
    volatile ap_uint<20> *awaddr_v = bw_awaddr;
    volatile ap_uint<20> *araddr_v = bw_araddr;

    ap_uint<88> curStatus = 0;
    for (int i = 0; i < MAX_STATUS_SLOTS; i++)
    {
#pragma HLS unroll
      curStatus(i * 4 + 3, i * 4) = status_stream[i].read();
    }
    ap_uint<1> start_gate_now = gate_stream.read();
    // Adjacent-cycle change detect, tracked every cycle (even pre-gate) so the
    // loop-carried lastStatus is primed by the time the gate opens. (The `if` here
    // folds to an unconditional `lastStatus = curStatus` -- `if(a!=b) b=a` == `b=a`
    // -- which is fine; the earlier stuck-at-0 was the `continue` splitting the loop,
    // not this update.)
    bool state_changed = (curStatus != lastStatus);
    if (state_changed)
      lastStatus = curStatus;

    // ---- start gate: NO `continue`. CRITICAL for HLS: a `continue` splits the loop
    // into a pre-header (FSM state1) + steady-state pipeline, and HLS then captured
    // curStatus into a register ONCE in the pre-header (status=0) and froze it -> the
    // change-detect compared a stuck-at-0 value, so nothing was ever written. With no
    // `continue` the body is one always-executed pipelined block, so curStatus stays
    // a per-iteration register. Only the SIDE EFFECTS (cycle count + the write) are
    // predicated on the start-gate latch; the pre-gate iterations sample/compare/track normally
    // (priming the pipeline) but emit nothing. ----
    ap_uint<1> started_before = started_latched;
    ap_uint<1> gate_fire = (started_before == 0) && (start_gate_now != 0);
    ap_uint<1> running_now = started_before | start_gate_now;
    started_latched = running_now;
    if (gate_fire != 0)
    {
      cycle_count = 0;
      win_cnt = 0;
      bwq_head = 0;
      bwq_valid = 0;
      addr_rot = 0;
      for (int p = 0; p < MAX_HBM_PORTS; p++)
      {
#pragma HLS unroll
        read_acc[p] = 0;
        write_acc[p] = 0;
      }
    }
    // Force the gate-open baseline write. After the gate the first real CHANGE
    // (the first accept) is captured because lastStatus has been tracking the idle
    // status every cycle.
    bool do_status = state_changed || (gate_fire != 0);
    if (running_now != 0)
      cycle_count++;

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
      ap_uint<32> r_sum = r_old + r_inc;
      ap_uint<32> w_sum = w_old + w_inc;
      // average over the window ending on this cycle, inclusive
      ap_uint<32> r_avg = r_sum >> WIN_SHIFT;
      ap_uint<32> w_avg = w_sum >> WIN_SHIFT;
      avg_r[p] = (r_avg > 255) ? (ap_uint<8>)255 : (ap_uint<8>)r_avg;
      avg_w[p] = (w_avg > 255) ? (ap_uint<8>)255 : (ap_uint<8>)w_avg;
      // reset after emitting the inclusive window, else accumulate
      read_acc[p] = window ? (ap_uint<32>)0 : r_sum;
      write_acc[p] = window ? (ap_uint<32>)0 : w_sum;
    }
    win_cnt = window ? (ap_uint<12>)0 : (ap_uint<12>)(win_cnt + 1);

    // ---- window boundary: build the 7 BW bundles into the (empty) queue ----
    // The previous window's <=7 bundles flushed within <=7 cycles, far inside
    // the next 128-cycle window, so the queue is always empty here. Skip windows
    // whose averaged read/write bytes are all zero to keep the trace compact.
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
      // [84:53] = the window-END cycle (this 128-cycle window covers cycles
      // [cycle-127 .. cycle]); lets the viewer time-anchor each BW set directly
      // instead of inferring it (robust to skipped idle windows / drops).
      ab(84, 53) = cycle_count;
      bwq[2 * N_BW_SUB] = ab;
      bwq_head = 0;
      // Seven bundles => valid bits [6:0]. Written as a scalable constant so it
      // follows N_BW_SUB if the monitored-port count changes.
      bwq_valid = (((ap_uint<BWQ_DEPTH>)1 << (2 * N_BW_SUB + 1)) - 1);
      addr_rot = (addr_rot + 1 >= MAX_HBM_PORTS) ? (ap_uint<5>)0 : (ap_uint<5>)(addr_rot + 1);
      }
    }

    // ---- assemble one beat: slot0 = status (if changed) else BW; slot1 = BW ----
    // Snapshot the persistent queue state, decide how many BW bundles this beat
    // consumes, then update head/validity exactly once. The former sequence of
    // conditional head/cnt/wrote mutations caused Vitis HLS to synthesize a
    // loop-carried phi path with reachable `X` fallbacks in hw_emu.
    const ap_uint<4> head = bwq_head;
    const ap_uint<BWQ_DEPTH> valid = bwq_valid;
    const bool have_bw0 = valid[0];
    const bool have_bw1 = valid[1];

    ap_uint<128> slot0 = 0; // header 0 = NULL
    ap_uint<128> slot1 = 0;
    ap_uint<2> consumed = 0;

    if (do_status)
    {
      ap_uint<128> st = 0;
      st(7, 0) = H_STATUS;
      st(95, 8) = curStatus;
      st(127, 96) = cycle_count;
      slot0 = st;

      // Preserve the existing STATUS+BW packing in the upper half of the beat.
      if (have_bw0)
      {
        slot1 = bwq[head];
        consumed = 1;
      }
    }
    else if (have_bw0)
    {
      slot0 = bwq[head];
      consumed = 1;

      // With no STATUS bundle, drain up to two BW bundles in this beat.
      if (have_bw1)
      {
        slot1 = bwq[head + 1];
        consumed = 2;
      }
    }

    const bool emit = do_status || have_bw0;
    bwq_head = head + consumed;
    bwq_valid = valid >> consumed;

    // Only emit after the start gate fires: pre-gate iterations sample/compare to prime the
    // pipeline but never write.
    if ((running_now != 0) && emit)
    {
      ap_uint<256> beat;
      beat(127, 0) = slot0;
      beat(255, 128) = slot1;
      write_queue.write(beat);
    }
  }
}
