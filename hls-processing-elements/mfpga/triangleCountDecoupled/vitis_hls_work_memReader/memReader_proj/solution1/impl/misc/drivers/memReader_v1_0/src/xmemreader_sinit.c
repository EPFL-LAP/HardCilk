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
#include "xmemreader.h"

extern XMemreader_Config XMemreader_ConfigTable[];

#ifdef SDT
XMemreader_Config *XMemreader_LookupConfig(UINTPTR BaseAddress) {
	XMemreader_Config *ConfigPtr = NULL;

	int Index;

	for (Index = (u32)0x0; XMemreader_ConfigTable[Index].Name != NULL; Index++) {
		if (!BaseAddress || XMemreader_ConfigTable[Index].Control_BaseAddress == BaseAddress) {
			ConfigPtr = &XMemreader_ConfigTable[Index];
			break;
		}
	}

	return ConfigPtr;
}

int XMemreader_Initialize(XMemreader *InstancePtr, UINTPTR BaseAddress) {
	XMemreader_Config *ConfigPtr;

	Xil_AssertNonvoid(InstancePtr != NULL);

	ConfigPtr = XMemreader_LookupConfig(BaseAddress);
	if (ConfigPtr == NULL) {
		InstancePtr->IsReady = 0;
		return (XST_DEVICE_NOT_FOUND);
	}

	return XMemreader_CfgInitialize(InstancePtr, ConfigPtr);
}
#else
XMemreader_Config *XMemreader_LookupConfig(u16 DeviceId) {
	XMemreader_Config *ConfigPtr = NULL;

	int Index;

	for (Index = 0; Index < XPAR_XMEMREADER_NUM_INSTANCES; Index++) {
		if (XMemreader_ConfigTable[Index].DeviceId == DeviceId) {
			ConfigPtr = &XMemreader_ConfigTable[Index];
			break;
		}
	}

	return ConfigPtr;
}

int XMemreader_Initialize(XMemreader *InstancePtr, u16 DeviceId) {
	XMemreader_Config *ConfigPtr;

	Xil_AssertNonvoid(InstancePtr != NULL);

	ConfigPtr = XMemreader_LookupConfig(DeviceId);
	if (ConfigPtr == NULL) {
		InstancePtr->IsReady = 0;
		return (XST_DEVICE_NOT_FOUND);
	}

	return XMemreader_CfgInitialize(InstancePtr, ConfigPtr);
}
#endif

#endif

