#include "test_bench_questa.h"

#include <stdint.h>
#include <iostream>


int sc_main(int argc, char **argv)
{
    TestBench testBench("TestBench");
    sc_start(SC_ZERO_TIME);
    sc_start(60000, SC_US);
    return 0;
}


#ifdef MTI_SYSTEMC
SC_MODULE_EXPORT(TestBench);
#endif
