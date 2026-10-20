#pragma once
// V80 stand-in for memIO_pcie.h. This directory is on the include path of a
// project's <spec>_v80 target ONLY when that project ships no src/v80_main.cpp:
// the target is then built from the U55C src/pcie_main.cpp, whose
// `#include <memIO_pcie.h>` / `PCIeMemory memory;` resolve to QdmaMemory here.
// Register offsets (RESET_ADDR 0x8000 included) are relative to the HardCilk
// management slave on both boards, so pcie_main.cpp needs no other change.
//
// The real U55C header (XDMA via svm/d4e) is never compiled into a _v80 target.
#include <FullSysGenDescriptor.h>
#include <memIO_qdma.h>

#ifdef HC_BOARD_U55C
#error "<spec>_v80 is being built against a U55C FullSysGenDescriptor.h; regenerate with --board v80"
#endif

struct PCIeMemory : QdmaMemory {
    PCIeMemory() : QdmaMemory(getBoardHbmBaseAddress(), getBoardHbmSizeBytes(), getBoardRegBaseAddress()) {}
};
