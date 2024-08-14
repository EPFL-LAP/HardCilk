// ==============================================================
// Vitis HLS - High-Level Synthesis from C, C++ and OpenCL v2024.1 (64-bit)
// Tool Version Limit: 2024.05
// Copyright 1986-2022 Xilinx, Inc. All Rights Reserved.
// Copyright 2022-2024 Advanced Micro Devices, Inc. All Rights Reserved.
// 
// ==============================================================
#ifndef __linux__

#include "xstatus.h"
#ifdef SDT
#include "xparameters.h"
#endif
#include "xfib.h"

extern XFib_Config XFib_ConfigTable[];

#ifdef SDT
XFib_Config *XFib_LookupConfig(UINTPTR BaseAddress) {
	XFib_Config *ConfigPtr = NULL;

	int Index;

	for (Index = (u32)0x0; XFib_ConfigTable[Index].Name != NULL; Index++) {
		if (!BaseAddress || XFib_ConfigTable[Index].Control_BaseAddress == BaseAddress) {
			ConfigPtr = &XFib_ConfigTable[Index];
			break;
		}
	}

	return ConfigPtr;
}

int XFib_Initialize(XFib *InstancePtr, UINTPTR BaseAddress) {
	XFib_Config *ConfigPtr;

	Xil_AssertNonvoid(InstancePtr != NULL);

	ConfigPtr = XFib_LookupConfig(BaseAddress);
	if (ConfigPtr == NULL) {
		InstancePtr->IsReady = 0;
		return (XST_DEVICE_NOT_FOUND);
	}

	return XFib_CfgInitialize(InstancePtr, ConfigPtr);
}
#else
XFib_Config *XFib_LookupConfig(u16 DeviceId) {
	XFib_Config *ConfigPtr = NULL;

	int Index;

	for (Index = 0; Index < XPAR_XFIB_NUM_INSTANCES; Index++) {
		if (XFib_ConfigTable[Index].DeviceId == DeviceId) {
			ConfigPtr = &XFib_ConfigTable[Index];
			break;
		}
	}

	return ConfigPtr;
}

int XFib_Initialize(XFib *InstancePtr, u16 DeviceId) {
	XFib_Config *ConfigPtr;

	Xil_AssertNonvoid(InstancePtr != NULL);

	ConfigPtr = XFib_LookupConfig(DeviceId);
	if (ConfigPtr == NULL) {
		InstancePtr->IsReady = 0;
		return (XST_DEVICE_NOT_FOUND);
	}

	return XFib_CfgInitialize(InstancePtr, ConfigPtr);
}
#endif

#endif

