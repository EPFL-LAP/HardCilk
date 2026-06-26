# ============================================================
# Minimal Makefile for the triangleCountDecoupled xclbin build
# (single-FPGA, no CMAC)
# ============================================================

.DEFAULT_GOAL := all

#--- Paths ---
MK_PATH     := $(abspath $(lastword $(MAKEFILE_LIST)))
CUR_DIR     := $(patsubst %/,%,$(dir $(MK_PATH)))

#--- Build target & platform ---
TARGET    ?= hw
PLATFORM  ?= xilinx_u55c_gen3x16_xdma_3_202210_1
DEVICE    ?= /opt/xilinx/platforms/xilinx_u55c_gen3x16_xdma_3_202210_1/xilinx_u55c_gen3x16_xdma_3_202210_1.xpfm

PLATFORM_NAME := $(strip $(patsubst %.xpfm,%,$(shell basename $(PLATFORM))))
XPLATFORM     := $(PLATFORM)

#--- Output directories ---
TEMP_DIR         := _x_temp.$(TARGET).$(PLATFORM_NAME)
BUILD_DIR        := build_dir.$(TARGET).$(PLATFORM_NAME)
BUILD_REPORT_DIR := $(CUR_DIR)/reports/_build.$(TARGET).$(PLATFORM_NAME)

#--- xclbin backups ---
# Every successful link drops a timestamped copy of the xclbin (and its matching
# hbmports.json descriptor, for telemetry decoding) here. Survives `make clean`
# (which only wipes the working build dirs); wiped by `make cleanall`.
BACKUP_DIR       := $(CUR_DIR)/xclbin-backups

#--- Tools ---
VPP    := v++
VIVADO := $(XILINX_VIVADO)/bin/vivado
EMCONFIGUTIL := emconfigutil

#--- Generated config files staged into src/cfg ---
SRC_CFG       := $(CUR_DIR)/src/cfg/conn_u55c.cfg
SRC_CFG_HW_EMU := $(CUR_DIR)/src/cfg/conn_u55c_hw_emu.cfg
ifeq ($(TARGET),hw_emu)
ifneq ($(wildcard $(SRC_CFG_HW_EMU)),)
SRC_CFG := $(SRC_CFG_HW_EMU)
endif
endif

#--- VPP flags ---
VPP_FLAGS   := -t $(TARGET) --platform $(XPLATFORM) --save-temps

VPP_LDFLAGS := --optimize 2 -R 2
VPP_LDFLAGS += --config $(SRC_CFG)

VPP_LINKHOOK_FLAGS :=
ifeq ($(TARGET),hw)
VPP_LINKHOOK_FLAGS += --linkhook.do_first vpl.impl.write_bitstream,scripts/clear_drc_errors.tcl
VPP_LINKHOOK_FLAGS += --linkhook.do_last  vpl.impl.opt_design,scripts/clear_drc_errors.tcl
endif

#--- Input .xo files ---
XO_ROOT     := ./triangleCountDecoupled/xo
XO_DIR      := $(XO_ROOT)/$(TARGET)
KERNEL_OBJS := $(XO_DIR)/whileLoopMain_reentry0_0.xo

#--- Output xclbin ---
XCLBIN := $(BUILD_DIR)/triangleCountDecoupled.xclbin

# ============================================================
# Build the RTL kernel XO from Vivado
# ============================================================
$(XO_DIR)/whileLoopMain_reentry0_0.xo: \
        ./src/xml/user_0.xml         \
        ./scripts/package_kernel.tcl \
        ./scripts/gen_xo.tcl         \
        ./src/IP/*.v
	mkdir -p $(XO_DIR)
	$(VIVADO) -mode batch -source scripts/gen_xo.tcl \
	    -tclargs $(XO_DIR)/whileLoopMain_reentry0_0.xo whileLoopMain_reentry0_0 $(TARGET) $(DEVICE) 0

# ============================================================
# Link XOs into the final xclbin
# ============================================================
$(XCLBIN): $(KERNEL_OBJS) $(SRC_CFG)
	mkdir -p $(BUILD_DIR) $(BUILD_REPORT_DIR)
	$(VPP)                    \
	    --vivado.synth.jobs 32 \
	    --vivado.impl.jobs  16 \
	    -l                     \
	    $(VPP_FLAGS)           \
	    $(VPP_LINKHOOK_FLAGS)  \
	    --temp_dir   $(TEMP_DIR)                             \
	    --report_dir $(BUILD_REPORT_DIR)/generator_collector \
	    $(VPP_LDFLAGS)         \
	    -o $@ $(KERNEL_OBJS)
	@mkdir -p $(BACKUP_DIR)
	@ts=$$(date +%Y%m%d_%H%M%S); \
	  cp $@ $(BACKUP_DIR)/triangleCountDecoupled.$(TARGET).$$ts.xclbin; \
	  if [ -f $(CUR_DIR)/triangleCountDecoupled.hbmports.json ]; then \
	    cp $(CUR_DIR)/triangleCountDecoupled.hbmports.json \
	       $(BACKUP_DIR)/triangleCountDecoupled.$(TARGET).$$ts.hbmports.json; \
	  fi; \
	  echo "[backup] saved $(BACKUP_DIR)/triangleCountDecoupled.$(TARGET).$$ts.xclbin"

# ============================================================
# Emulation config (hw_emu only)
# ============================================================
emconfig:
	$(EMCONFIGUTIL) --platform $(XPLATFORM) --od $(BUILD_DIR)

# ============================================================
# Convenience targets
# ============================================================
.PHONY: all xo clean cleanall emconfig

all: $(XCLBIN)

xo: $(XO_DIR)/whileLoopMain_reentry0_0.xo

clean:
	-rm -rf $(BUILD_DIR) $(TEMP_DIR) *.log *.jou

cleanall: clean
	-rm -rf _x_temp.* build_dir.* $(CUR_DIR)/reports $(XO_ROOT)
	-rm -rf .ipcache .Xil
	-rm -rf packaged_kernel_*
	-rm -rf tmp_kernel_pack_*
	-rm -rf emconfig.json
	-rm -rf $(BACKUP_DIR)
