/*
 * qsort.c
 *
 * Copyright (c) 2007, 2008 Cilk Arts, Inc.  All rights reserved.
 *
 * An implementation of quicksort using Cilk parallelization.
 */

#include "descriptors.h"

void swap(int a, int b, void* mem)
{
#pragma HLS INTERFACE mode=m_axi port=mem
	int val_a = MEM_IN(mem, a, int);
	int val_b = MEM_IN(mem, b, int);
	MEM_OUT(mem, a, int, val_b);
	MEM_OUT(mem, b, int, val_a);
}

// Partition array using last element of array as pivot
// (move elements less than last to lower partition
// and elements not less than last to upper partition
// return middle = the first element not less than last
uint64_t partition(uint64_t begin, uint64_t end, int pivot, void* mem)
{
#pragma HLS INTERFACE mode=m_axi port=mem

	partition_label0: while (begin < end)
	{
#pragma HLS PIPELINE off
		if (MEM_IN(mem, begin, int) < pivot)
		{
			begin += sizeof(int);
		}
		else
		{
			end -= sizeof(int);
			swap(begin, end, mem);
		}
	}
	return end;
}

// Sort the range between pointers begin and end.
// end is one past the final element in the range.
// Use the Quick Sort algorithm, using recursive divide and conquer.
void qsort(hls::stream<qsort_args> &taskIn,
        hls::stream<qsort_args> &taskOut,
		hls::stream<uint64_t> &argOut,
		hls::stream<uint64_t> &closureIn,
		void* mem)
{

#pragma HLS INTERFACE mode=axis port=taskIn
#pragma HLS INTERFACE mode=axis port=taskOut
#pragma HLS INTERFACE mode=axis port=closureIn
#pragma HLS INTERFACE mode=axis port=argOut
#pragma HLS INTERFACE mode=m_axi port=mem

	qsort_args args = taskIn.read();
	uint64_t begin = args.begin;
	uint64_t end = args.end;

	// get last element
	int last = MEM_IN(mem, end-sizeof(int), int);

	// we give partition a pointer to the first element and one past the last element
	// of the range we want to partition
	// move all values which are >= last to the end
	// move all value which are < last to the beginning
	// return a pointer to the first element >= last
	int middle = partition(begin, end - sizeof(int), last, mem);

	// move pivot to middle
	swap((end - sizeof(int)), middle, mem);

	// spawn_next
	int counter = 0;
	if (middle + sizeof(int) < end)
		counter++;
	if (begin < middle)
		counter++;

	uint64_t new_closure = closureIn.read();
	sync_args sync_args0;
	sync_args0.counter = counter;
	sync_args0.cont = args.cont;


	// spawn arguments
	qsort_args arg0;
	arg0.cont = new_closure;
	arg0.begin = middle + sizeof(int);
	arg0.end = end;

	qsort_args arg1;
	arg1.cont = new_closure;
	arg1.begin = begin;
	arg1.end = middle;

	// do mem stuff

	io_section:
	{
		MEM_OUT(mem, new_closure, sync_args, sync_args0);
		ap_wait_until(MEM_IN(mem, new_closure + 8, uint64_t) == args.cont);
		if (middle + sizeof(int) < end) {
			taskOut.write(arg0);
		}
		if (begin < middle) {
			taskOut.write(arg1);
		}
	}

    if(counter == 0){
        argOut.write(args.cont);
    }

}
