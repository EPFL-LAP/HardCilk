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

// One PE's captured raw handshake bits (4 x 1 byte in the natural layout). From
// these the viewer derives: empty=!valid, full=valid&&!ready, consumed=
// in_valid&&in_ready, pushed=out_valid&&out_ready.
struct PackedPEStatus
{
  ap_uint<1> in_valid;
  ap_uint<1> in_ready;
  ap_uint<1> out_valid;
  ap_uint<1> out_ready;
};

// 128-bit payload to be written to memory
struct TelemetryBundle
{
  // 48 bits of actual PE status data
  PackedPEStatus cont0[N_whileLoopMain_reentry0_cont0]; // 16 bits
  PackedPEStatus memReader[N_memReader];                // 16 bits
  PackedPEStatus reentry0[N_whileLoopMain_reentry0];    // 16 bits

  // 16 bits of explicit padding to ensure perfect AXI alignment
  ap_uint<16> padding;

  // 64-bit absolute cycle counter (Will practically never overflow)
  ap_uint<64> cycle_count;
};

// --------------------------------------------------------
// WATCHER KERNEL
// --------------------------------------------------------

void watcher(
    TelemetryBundle *mem, // 128-bit aligned AXI Master pointer
    uint64_t start_addr,  // Assuming this is a byte offset provided by the host
    PEStatus cont0_status[N_whileLoopMain_reentry0_cont0],
    PEStatus memReader_status[N_memReader],
    PEStatus reentry0_status[N_whileLoopMain_reentry0])
{
// Memory Write Port: AXI4 Master
#pragma HLS INTERFACE mode = m_axi port = mem offset = direct
#pragma HLS INTERFACE mode = ap_none port = start_addr
#pragma HLS INTERFACE ap_ctrl_none port = return

// Shred array into discrete hardware pins
#pragma HLS ARRAY_PARTITION variable = cont0_status complete dim = 1
#pragma HLS DISAGGREGATE variable = cont0_status
#pragma HLS INTERFACE mode = ap_none port = cont0_status

#pragma HLS ARRAY_PARTITION variable = memReader_status complete dim = 1
#pragma HLS DISAGGREGATE variable = memReader_status
#pragma HLS INTERFACE mode = ap_none port = memReader_status

#pragma HLS ARRAY_PARTITION variable = reentry0_status complete dim = 1
#pragma HLS DISAGGREGATE variable = reentry0_status
#pragma HLS INTERFACE mode = ap_none port = reentry0_status

  // Internal states
  static TelemetryBundle lastStatus;
  static uint64_t write_idx = 0;
  static uint64_t absolute_cycle_count = 0;
  // Prime guard: the VERY FIRST state-change after reset would issue an AXI
  // write before the m_axi write-address pipeline register has ever been loaded,
  // driving an X (undefined) address with no aligned data. That beat is accepted
  // on AW but never completes on W, desyncing the AW/W channels and permanently
  // wedging the AXI write engine (so NO telemetry is ever written). Fix: consume
  // the first detected change as a "prime" (update lastStatus, do NOT write); by
  // the second change the address register has been loaded (it loads every loop
  // iteration) so the address is valid. This is timing-independent -- it skips
  // exactly one telemetry sample regardless of clock/run length.
  static bool primed = false;

  // Convert byte-address to 128-bit word index
  uint64_t base_idx = start_addr / sizeof(TelemetryBundle);

  while (true)
  {
#pragma HLS pipeline II = 1

    // This ticks every clock cycle and ignores pipeline backpressure
    absolute_cycle_count++;

    TelemetryBundle currentStatus;

    // Force bit-level packing into 128-bit registers
#pragma HLS AGGREGATE variable = currentStatus compact = bit
#pragma HLS AGGREGATE variable = lastStatus compact = bit

    // Clear padding explicitly to prevent uninitialized memory issues
    currentStatus.padding = 0;

    bool state_changed = false;

    // Read the ap_none status ports through VOLATILE pointers so HLS performs a
    // fresh read every iteration (without volatile HLS treats the reads as
    // loop-invariant and hoists them into the loop preheader, sampling status
    // exactly ONCE at startup -> state_changed never fires). Per-field SCALAR
    // volatile reads (below) are not hoisted; an aggregate ap_uint<2> volatile
    // read IS hoisted by HLS, which silently breaks change detection. Bit-level
    // coherence comes from the single uniform RegNext stage in connectWatcher.
    volatile PEStatus *cont0_v = cont0_status;
    volatile PEStatus *memReader_v = memReader_status;
    volatile PEStatus *reentry0_v = reentry0_status;

    // Pack and Compare cont0
    for (int i = 0; i < N_whileLoopMain_reentry0_cont0; i++)
    {
#pragma HLS unroll
      currentStatus.cont0[i].in_valid = cont0_v[i].in.valid;
      currentStatus.cont0[i].in_ready = cont0_v[i].in.ready;
      currentStatus.cont0[i].out_valid = cont0_v[i].out.valid;
      currentStatus.cont0[i].out_ready = cont0_v[i].out.ready;

      if (currentStatus.cont0[i].in_valid != lastStatus.cont0[i].in_valid ||
          currentStatus.cont0[i].in_ready != lastStatus.cont0[i].in_ready ||
          currentStatus.cont0[i].out_valid != lastStatus.cont0[i].out_valid ||
          currentStatus.cont0[i].out_ready != lastStatus.cont0[i].out_ready)
      {
        state_changed = true;
      }
    }

    // Pack and Compare memReader
    for (int i = 0; i < N_memReader; i++)
    {
#pragma HLS unroll
      currentStatus.memReader[i].in_valid = memReader_v[i].in.valid;
      currentStatus.memReader[i].in_ready = memReader_v[i].in.ready;
      currentStatus.memReader[i].out_valid = memReader_v[i].out.valid;
      currentStatus.memReader[i].out_ready = memReader_v[i].out.ready;

      if (currentStatus.memReader[i].in_valid != lastStatus.memReader[i].in_valid ||
          currentStatus.memReader[i].in_ready != lastStatus.memReader[i].in_ready ||
          currentStatus.memReader[i].out_valid != lastStatus.memReader[i].out_valid ||
          currentStatus.memReader[i].out_ready != lastStatus.memReader[i].out_ready)
      {
        state_changed = true;
      }
    }

    // Pack and Compare reentry0
    for (int i = 0; i < N_whileLoopMain_reentry0; i++)
    {
#pragma HLS unroll
      currentStatus.reentry0[i].in_valid = reentry0_v[i].in.valid;
      currentStatus.reentry0[i].in_ready = reentry0_v[i].in.ready;
      currentStatus.reentry0[i].out_valid = reentry0_v[i].out.valid;
      currentStatus.reentry0[i].out_ready = reentry0_v[i].out.ready;

      if (currentStatus.reentry0[i].in_valid != lastStatus.reentry0[i].in_valid ||
          currentStatus.reentry0[i].in_ready != lastStatus.reentry0[i].in_ready ||
          currentStatus.reentry0[i].out_valid != lastStatus.reentry0[i].out_valid ||
          currentStatus.reentry0[i].out_ready != lastStatus.reentry0[i].out_ready)
      {
        state_changed = true;
      }
    }

    // Execute AXI Write only if the hardware state has actually changed.
    if (state_changed)
    {
      if (primed)
      {
        // Attach the absolute timestamp right before writing
        currentStatus.cycle_count = absolute_cycle_count;

        // Standard pointer arithmetic maps directly to AXI burst/write logic
        mem[base_idx + write_idx] = currentStatus;

        write_idx++; // Increment to next 128-bit slot
      }
      else
      {
        // Consume the first change as the prime: skip the write (avoids the
        // X-address beat that wedges the engine), just record the new state.
        primed = true;
      }
      lastStatus = currentStatus;
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
#pragma HLS INTERFACE mode = axis port = argDataOut
#pragma HLS INTERFACE mode = m_axi port = mem
#pragma HLS INTERFACE ap_ctrl_none port = return
#pragma HLS PIPELINE II = 1 style = flp

  memReader_task args = taskIn.read();

  argOut.write(args._cont);
  uint32_t_arg_out a0;
  a0.addr = args._cont;
  a0.data = MEM_ARR_IN(mem, args.mem, args.idx, int);
  a0.size = 2;
  a0.allow = 1;
  argDataOut.write(a0);
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
