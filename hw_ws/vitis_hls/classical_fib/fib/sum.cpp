#include "fib.h"
#include <cstdint>
#include <etc/autopilot_ssdm_op.h>

void sum(hls::stream<sum_args> &taskIn, 
        hls::stream<uint64_t> &argOut,
        void * mem){

    #pragma HLS INTERFACE mode=axis port=taskIn
    #pragma HLS INTERFACE mode=axis port=argOut
    #pragma HLS INTERFACE mode=m_axi port=mem

    sum_args args = taskIn.read();
    uint32_t sum = args.x+args.y;
    io_section:{
        MEM_OUT(mem, args.cont, uint32_t, sum);
        #pragma HLS PROTOCOL mode=fixed
        ap_wait_until(MEM_IN(mem, args.cont, uint32_t) == sum);
        argOut.write(args.cont);
    }
}