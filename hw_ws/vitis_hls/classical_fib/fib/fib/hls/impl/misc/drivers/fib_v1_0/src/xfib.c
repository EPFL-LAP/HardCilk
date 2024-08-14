// ==============================================================
// Vitis HLS - High-Level Synthesis from C, C++ and OpenCL v2024.1 (64-bit)
// Tool Version Limit: 2024.05
// Copyright 1986-2022 Xilinx, Inc. All Rights Reserved.
// Copyright 2022-2024 Advanced Micro Devices, Inc. All Rights Reserved.
// 
// ==============================================================
/***************************** Include Files *********************************/
#include "xfib.h"

/************************** Function Implementation *************************/
#ifndef __linux__
int XFib_CfgInitialize(XFib *InstancePtr, XFib_Config *ConfigPtr) {
    Xil_AssertNonvoid(InstancePtr != NULL);
    Xil_AssertNonvoid(ConfigPtr != NULL);

    InstancePtr->Control_BaseAddress = ConfigPtr->Control_BaseAddress;
    InstancePtr->IsReady = XIL_COMPONENT_IS_READY;

    return XST_SUCCESS;
}
#endif

void XFib_Set_mem(XFib *InstancePtr, u64 Data) {
    Xil_AssertVoid(InstancePtr != NULL);
    Xil_AssertVoid(InstancePtr->IsReady == XIL_COMPONENT_IS_READY);

    XFib_WriteReg(InstancePtr->Control_BaseAddress, XFIB_CONTROL_ADDR_MEM_DATA, (u32)(Data));
    XFib_WriteReg(InstancePtr->Control_BaseAddress, XFIB_CONTROL_ADDR_MEM_DATA + 4, (u32)(Data >> 32));
}

u64 XFib_Get_mem(XFib *InstancePtr) {
    u64 Data;

    Xil_AssertNonvoid(InstancePtr != NULL);
    Xil_AssertNonvoid(InstancePtr->IsReady == XIL_COMPONENT_IS_READY);

    Data = XFib_ReadReg(InstancePtr->Control_BaseAddress, XFIB_CONTROL_ADDR_MEM_DATA);
    Data += (u64)XFib_ReadReg(InstancePtr->Control_BaseAddress, XFIB_CONTROL_ADDR_MEM_DATA + 4) << 32;
    return Data;
}

