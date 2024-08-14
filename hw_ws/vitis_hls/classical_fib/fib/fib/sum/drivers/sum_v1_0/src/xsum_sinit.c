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
#include "xsum.h"

extern XSum_Config XSum_ConfigTable[];

#ifdef SDT
XSum_Config *XSum_LookupConfig(UINTPTR BaseAddress) {
	XSum_Config *ConfigPtr = NULL;

	int Index;

	for (Index = (u32)0x0; XSum_ConfigTable[Index].Name != NULL; Index++) {
		if (!BaseAddress || XSum_ConfigTable[Index].Control_BaseAddress == BaseAddress) {
			ConfigPtr = &XSum_ConfigTable[Index];
			break;
		}
	}

	return ConfigPtr;
}

int XSum_Initialize(XSum *InstancePtr, UINTPTR BaseAddress) {
	XSum_Config *ConfigPtr;

	Xil_AssertNonvoid(InstancePtr != NULL);

	ConfigPtr = XSum_LookupConfig(BaseAddress);
	if (ConfigPtr == NULL) {
		InstancePtr->IsReady = 0;
		return (XST_DEVICE_NOT_FOUND);
	}

	return XSum_CfgInitialize(InstancePtr, ConfigPtr);
}
#else
XSum_Config *XSum_LookupConfig(u16 DeviceId) {
	XSum_Config *ConfigPtr = NULL;

	int Index;

	for (Index = 0; Index < XPAR_XSUM_NUM_INSTANCES; Index++) {
		if (XSum_ConfigTable[Index].DeviceId == DeviceId) {
			ConfigPtr = &XSum_ConfigTable[Index];
			break;
		}
	}

	return ConfigPtr;
}

int XSum_Initialize(XSum *InstancePtr, u16 DeviceId) {
	XSum_Config *ConfigPtr;

	Xil_AssertNonvoid(InstancePtr != NULL);

	ConfigPtr = XSum_LookupConfig(DeviceId);
	if (ConfigPtr == NULL) {
		InstancePtr->IsReady = 0;
		return (XST_DEVICE_NOT_FOUND);
	}

	return XSum_CfgInitialize(InstancePtr, ConfigPtr);
}
#endif

#endif

