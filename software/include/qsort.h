#ifndef QSORT_H
#define QSORT_H

#include <stdint.h>
#include <hardCilkDriver.h>

/**
 * @brief Descriptor for a qsort task
 * 
 * This struct is used to describe a qsort task to the FPGA.
 */
typedef struct {
  uint64_t returnAddress;
  uint64_t begin;
  uint64_t end;
  uint64_t padding;
} qsortSpatialDescriptor;

qsortSpatialDescriptor create_qsort_task(hardCilkDriver *driver, int sortSize);

bool check_sorted(int *array, int size);

#endif // QSORT_H