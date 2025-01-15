#include "fib.h"
#include <cstdint>
#include <etc/autopilot_ssdm_op.h>

void fib(hls::stream<fib_args> &taskIn, 
        hls::stream<fib_args> &taskOut, 
        hls::stream<uint64_t> &argOut, 
        hls::stream<uint64_t> &closureIn, 
        void *mem) {

    #pragma HLS INTERFACE mode=axis port=taskIn 
    #pragma HLS INTERFACE mode=axis port=taskOut
    #pragma HLS INTERFACE mode=axis port=argOut 
    #pragma HLS INTERFACE mode=axis port=closureIn 
    #pragma HLS INTERFACE mode=m_axi port=mem 


    fib_args args = taskIn.read();
    

    if (args.n <= 1) {
        io_section_0:{
            MEM_OUT(mem, args.cont, uint32_t, args.n);  
            #pragma HLS PROTOCOL mode=fixed
            ap_wait_until(MEM_IN(mem, args.cont, uint32_t) == args.n);
            argOut.write((uint64_t)args.cont);
        }
    } else {
        sum_args sum_args0;
        sum_args0.cont = args.cont;
        sum_args0.join_counter = 2;
        uint64_t sum_addr = closureIn.read();
        uint64_t x_addr = sum_addr + 0x4;
        uint64_t y_addr = sum_addr + 0x8;
        fib_args fib_args1;
        fib_args fib_args2;
        fib_args1.cont = x_addr;
        fib_args1.n = args.n - 1;
        fib_args2.cont = y_addr;
        fib_args2.n = args.n - 2;

        io_section_1:{
            MEM_OUT(mem, sum_addr, sum_args, sum_args0);
            //#pragma HLS PROTOCOL mode=fixed        
            sum_args tmp = MEM_IN(mem, sum_addr, sum_args);
            ap_wait_until(tmp.join_counter == sum_args0.join_counter);
            ap_wait_until(tmp.cont == sum_args0.cont);
            taskOut.write(fib_args1);   
            ap_wait();
            taskOut.write(fib_args2);
            ap_wait();   
        }

    }
}