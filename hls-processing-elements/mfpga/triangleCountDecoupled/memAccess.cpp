#include "hls_stream.h"
#include "memAccess_defs.h"
#include <ap_int.h>
#include <stdint.h>

void whileLoopMain_reentry0_cont0(
    hls::stream<whileLoopMain_reentry0_cont0_task> &taskIn,
    hls::stream<whileLoopMain_reentry0_task> &taskOutGlobal)
{
#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = taskOutGlobal
#pragma HLS INTERFACE ap_ctrl_none port = return
#pragma HLS PIPELINE II = 1 style = flp

  whileLoopMain_reentry0_cont0_task args = taskIn.read();

  // The count lives in the closure now -- no HBM accumulator, so this PE needs no
  // AXI port at all. The recursion is a single sequential chain
  // (reentry0(i,j) -> memReaders -> cont0(i,j) -> reentry0(...)), so bumping the
  // carried running value is the exact accumulation with no RMW hazard.
  addr_t count = args.count;
  if ((args.a_i == args.b_j))
  {
    count++;
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
  whileLoopMain_reentry0_args0.count = count;
  whileLoopMain_reentry0_args0.count_final = args.count_final;
  whileLoopMain_reentry0_args0.size = args.size;
  whileLoopMain_reentry0_args0.i = args.i;
  whileLoopMain_reentry0_args0.j = args.j;
  taskOutGlobal.write(whileLoopMain_reentry0_args0);
}

// void whileLoopMain_exit0(
//     hls::stream<whileLoopMain_exit0_task> &taskIn,
//     hls::stream<uint64_t> &argOut)
// {
// #pragma HLS INTERFACE mode = axis port = taskIn
// #pragma HLS INTERFACE mode = axis port = argOut
// #pragma HLS INTERFACE ap_ctrl_none port = return
// #pragma HLS PIPELINE II = 1 style = flp

//   whileLoopMain_exit0_task args = taskIn.read();
//   argOut.write(args._cont);
// }

void memReader(
    void *mem,
    hls::stream<memReader_task> &taskIn,
#if COUNTDECOUPLED_LEGACY_ARGUMENT_NOTIFIER
    hls::stream<uint64_t> &argOut,
    hls::stream<uint32_t_arg_out> &argDataOut)
#else
    hls::stream<whileLoopMain_reentry0_cont0_argument_update> &argOut)
#endif
{
#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = argOut
#if COUNTDECOUPLED_LEGACY_ARGUMENT_NOTIFIER
#pragma HLS INTERFACE mode = axis register_mode = off port = argDataOut
#endif
#pragma HLS INTERFACE mode = m_axi port = mem
#pragma HLS INTERFACE ap_ctrl_none port = return
#pragma HLS PIPELINE II = 1 style = flp

  memReader_task args = taskIn.read();

#if COUNTDECOUPLED_LEGACY_ARGUMENT_NOTIFIER
    const addr_t continuationAddress =
        args._cont + addr_t(args.offset) * sizeof(uint32_t);
    uint32_t_arg_out update;
    update.addr = continuationAddress;
    update.data = MEM_ARR_IN(mem, args.mem, args.idx, int);
    update.size = 2;
    update.allow = 1;
    argDataOut.write(update);
    argOut.write(continuationAddress);
#else
    whileLoopMain_reentry0_cont0_argument_update update;
    update.address = args._cont;
    update.continuation_meta = args.continuation_meta;
    update.payload = MEM_ARR_IN(mem, args.mem, args.idx, int);
    update.offset = args.offset;
    argOut.write(update);
#endif
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
  whileLoopMain_task args = taskIn.read();

  i = 0;
  j = 0;
  whileLoopMain_reentry0_task whileLoopMain_reentry0_args1;
  whileLoopMain_reentry0_args1._cont = args._cont;
  whileLoopMain_reentry0_args1.A = args.A;
  whileLoopMain_reentry0_args1.B = args.B;
  whileLoopMain_reentry0_args1.count = args.count;
  whileLoopMain_reentry0_args1.count_final = args.count_final;
  whileLoopMain_reentry0_args1.size = args.size;
  whileLoopMain_reentry0_args1.i = i;
  whileLoopMain_reentry0_args1.j = j;
  taskOutGlobal.write(whileLoopMain_reentry0_args1);
}

void whileLoopMain_reentry0(
    void *mem,
    hls::stream<whileLoopMain_reentry0_task> &taskIn,
    hls::stream<memReader_task> &taskOutGlobal1,
    hls::stream<memReader_task> &taskOutGlobal2,
    hls::stream<uint64_t> &closureIn,
    hls::stream<whileLoopMain_reentry0_cont0_spawn_next> &spawnNext)
{
#pragma HLS INTERFACE mode = m_axi port = mem
#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = taskOutGlobal1
#pragma HLS INTERFACE mode = axis port = taskOutGlobal2
#pragma HLS INTERFACE mode = axis port = closureIn
#pragma HLS INTERFACE mode = axis port = spawnNext
#pragma HLS INTERFACE ap_ctrl_none port = return
#pragma HLS PIPELINE II = 1 style = flp

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
    SN_whileLoopMain_reentry0_cont0c.count_final = args.count_final;
    SN_whileLoopMain_reentry0_cont0c.B = args.B;
    SN_whileLoopMain_reentry0_cont0c.A = args.A;
    // Cached argument updates are OR-merged into this base continuation, so
    // fields populated by the memReaders must start clear.
    SN_whileLoopMain_reentry0_cont0c.a_i = 0;
    SN_whileLoopMain_reentry0_cont0c.b_j = 0;
    whileLoopMain_reentry0_cont0_spawn_next SN_whileLoopMain_reentry0_cont0;
    SN_whileLoopMain_reentry0_cont0.addr = SN_whileLoopMain_reentry0_cont0c_k;
    SN_whileLoopMain_reentry0_cont0.data = SN_whileLoopMain_reentry0_cont0c;
    SN_whileLoopMain_reentry0_cont0.size = 6;
    SN_whileLoopMain_reentry0_cont0.allow0 = 1;
    SN_whileLoopMain_reentry0_cont0.allow1 = 1;
    spawnNext.write(SN_whileLoopMain_reentry0_cont0);

    memReader_task memReader_args2;
    memReader_args2._cont = SN_whileLoopMain_reentry0_cont0c_k;
    // The spawnNext write buffer replaces this with the metadata assigned by
    // NewArgumentNotifier before releasing the child.
    memReader_args2.continuation_meta = 0;
    memReader_args2.mem = args.A;
    memReader_args2.idx = args.i;
    memReader_args2.offset =
        offsetof(whileLoopMain_reentry0_cont0_task, a_i) / sizeof(uint32_t);
    taskOutGlobal1.write(memReader_args2);

    memReader_task memReader_args3;
    memReader_args3._cont = SN_whileLoopMain_reentry0_cont0c_k;
    memReader_args3.continuation_meta = 0;
    memReader_args3.mem = args.B;
    memReader_args3.idx = args.j;
    memReader_args3.offset =
        offsetof(whileLoopMain_reentry0_cont0_task, b_j) / sizeof(uint32_t);
    taskOutGlobal2.write(memReader_args3);
  }
  else
  {
    // Commit the final count and done flag as one 8-byte store.
    uint64_t done_word = ((uint64_t)1 << 32) | (uint32_t)args.count;
    MEM_OUT(mem, args.count_final, uint64_t, done_word);
  }
}
