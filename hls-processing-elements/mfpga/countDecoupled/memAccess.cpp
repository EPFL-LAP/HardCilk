#include "hls_stream.h"
#include "memAccess_defs.h"
#include <ap_int.h>
#include <stdint.h>

// Optional emulation-only memReader congestion injection; default is functionally
// identical to an unconditional blocking taskIn.read().
#ifndef CONGESTION_INJECT
#define CONGESTION_INJECT 0
#endif
#ifndef CONGESTION_STALL_HIBIT
#define CONGESTION_STALL_HIBIT 12
#endif
#ifndef CONGESTION_STALL_LOBIT
#define CONGESTION_STALL_LOBIT 11
#endif

void taskAdder_cont0(
    hls::stream<taskAdder_cont0_task> &taskIn,
    hls::stream<taskInitiator_reentry0_task> &taskOutGlobal)
{

#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = taskOutGlobal
#pragma HLS INTERFACE ap_ctrl_none port = return
#pragma HLS PIPELINE II = 1 style = flp

  taskAdder_cont0_task args = taskIn.read();

  // The count lives in the closure now -- no HBM accumulator, so this PE needs no
  // AXI port at all. The recursion is a single sequential chain
  // (initiator(i) -> memReader(i) -> taskAdder(i) -> initiator(i+1)), so bumping
  // the carried running value is the exact accumulation with no RMW hazard.
  addr_t count = args.count;
  if (args.value == 1)
  {
    count++;
  }
  (args.i++);

  taskInitiator_reentry0_task taskInitiator_reentry0_args0;
  taskInitiator_reentry0_args0._cont = args._cont;
  taskInitiator_reentry0_args0.continuation_meta = args.continuation_meta;
  taskInitiator_reentry0_args0.A = args.A;
  taskInitiator_reentry0_args0.count = count;
  taskInitiator_reentry0_args0.count_final = args.count_final;
  taskInitiator_reentry0_args0.size = args.size;
  taskInitiator_reentry0_args0.i = args.i;
  taskOutGlobal.write(taskInitiator_reentry0_args0);
}
void memReader(
    void *mem,
    hls::stream<memReader_task> &taskIn,
#if COUNTDECOUPLED_LEGACY_ARGUMENT_NOTIFIER
    hls::stream<uint64_t> &argOut,
    hls::stream<uint32_t_arg_out> &argDataOut)
#else
    hls::stream<taskAdder_cont0_argument_update> &argOut)
#endif
{

#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = argOut
#if COUNTDECOUPLED_LEGACY_ARGUMENT_NOTIFIER
#pragma HLS INTERFACE mode = axis register_mode = off port = argDataOut
#endif
#pragma HLS INTERFACE mode = m_axi port = mem max_widen_bitwidth=256
#pragma HLS cache port=mem lines=2048 depth=32 // 64 KB. VCD-measured memReader working set ~529 instances / max reuse 519 -> 512 lines thrashed (capacity+direct-mapped conflicts from the idx spread). 2048 gives 4x headroom and doubles the conflict period. depth=32B = one 256-bit beat/line
#pragma HLS INTERFACE ap_ctrl_none port = return
#pragma HLS PIPELINE II = 1 style = flp

#if CONGESTION_INJECT
  static ap_uint<32> cong_cycle = 0;
  cong_cycle++;
  bool cong_stall =
      cong_cycle(CONGESTION_STALL_HIBIT, CONGESTION_STALL_LOBIT) ==
      ap_uint<CONGESTION_STALL_HIBIT - CONGESTION_STALL_LOBIT + 1>(-1);
  memReader_task args;
  bool got = !cong_stall && taskIn.read_nb(args);
#else
  memReader_task args = taskIn.read();
  bool got = true;
#endif

  if (got)
  {
#if COUNTDECOUPLED_LEGACY_ARGUMENT_NOTIFIER
    uint32_t_arg_out update;
    update.addr = args._cont;
    update.data = MEM_ARR_IN(mem, args.mem, args.idx, int);
    update.size = 2;
    update.allow = 1;
    argDataOut.write(update);
    argOut.write(args._cont);
#else
    constexpr unsigned valueBit = offsetof(taskAdder_cont0_task, value) * 8;
    taskAdder_cont0_argument_update update;
    update.address = args._cont;
    update.continuation_meta = args.continuation_meta;
    update.dataWrite = 0;
    update.dataWrite.range(valueBit + 31, valueBit) =
        MEM_ARR_IN(mem, args.mem, args.idx, int);
    update.dataWriteStrobe = 0;
    update.dataWriteStrobe.range(valueBit + 31, valueBit) = ap_uint<32>(-1);
    argOut.write(update);
#endif
  }
}

void taskInitiator_reentry0(
    void *mem,
    hls::stream<taskInitiator_reentry0_task> &taskIn,
    hls::stream<memReader_task> &taskOutGlobal,
    hls::stream<uint64_t> &closureIn,
    hls::stream<taskAdder_cont0_spawn_next> &spawnNext)
{

#pragma HLS INTERFACE mode = m_axi port = mem
#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = taskOutGlobal
#pragma HLS INTERFACE mode = axis port = closureIn
#pragma HLS INTERFACE mode = axis port = spawnNext
#pragma HLS INTERFACE ap_ctrl_none port = return
#pragma HLS PIPELINE II = 1 style = flp

  taskInitiator_reentry0_task args = taskIn.read();

  if (args.i < args.size)
  {
    uint32_t SN_taskAdder_cont0c_cnt = 1;
    taskAdder_cont0_task SN_taskAdder_cont0c;
    SN_taskAdder_cont0c._cont = args._cont;
    SN_taskAdder_cont0c.continuation_meta = args.continuation_meta;
    SN_taskAdder_cont0c._counter = SN_taskAdder_cont0c_cnt;
    addr_t SN_taskAdder_cont0c_k = closureIn.read();

    SN_taskAdder_cont0c.i = args.i;
    SN_taskAdder_cont0c.size = args.size;
    SN_taskAdder_cont0c.count = args.count;
    SN_taskAdder_cont0c.count_final = args.count_final;
    SN_taskAdder_cont0c.A = args.A;
    SN_taskAdder_cont0c._value_pad = 0;
    SN_taskAdder_cont0c.value = 0;
    taskAdder_cont0_spawn_next SN_taskAdder_cont0;
    SN_taskAdder_cont0.addr = SN_taskAdder_cont0c_k;
    SN_taskAdder_cont0.data = SN_taskAdder_cont0c;
    SN_taskAdder_cont0.size = 6;
    SN_taskAdder_cont0.allow = SN_taskAdder_cont0c_cnt;
    spawnNext.write(SN_taskAdder_cont0);

    memReader_task memReader_args2;
    memReader_args2._cont = SN_taskAdder_cont0c_k + offsetof(taskAdder_cont0_task, value);
    // The spawnNext write buffer replaces this with the metadata assigned by
    // NewArgumentNotifier before releasing the child.
    memReader_args2.continuation_meta = 0;
    memReader_args2.mem = args.A;
    memReader_args2.idx = args.i;
    taskOutGlobal.write(memReader_args2);
  }
  else
  {
    // Done: commit the final count + done flag as ONE 8-byte store so the host can
    // never observe done=1 with a stale result (avoids the write-reorder hazard of
    // two separate stores on the same port). Little-endian layout:
    //   bytes [0..3] = final count   (host reads int32 @ count_final)
    //   bytes [4..7] = done flag = 1 (host polls int32 @ count_final + 4)
    uint64_t done_word = ((uint64_t)1 << 32) | (uint32_t)args.count;
    MEM_OUT(mem, args.count_final, uint64_t, done_word);
  }
}
