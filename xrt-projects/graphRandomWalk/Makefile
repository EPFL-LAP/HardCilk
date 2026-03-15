# ============================================================
# Minimal Makefile for VNx graphRandomWalk xclbin build
# ============================================================

#--- Paths ---
MK_PATH     := $(abspath $(lastword $(MAKEFILE_LIST)))
CUR_DIR     := $(patsubst %/,%,$(dir $(MK_PATH)))

#--- Build target & platform ---
TARGET    ?= hw
PLATFORM  ?= xilinx_u55c_gen3x16_xdma_3_202210_1
DEVICE    ?= /opt/xilinx/platforms/xilinx_u55c_gen3x16_xdma_3_202210_1/xilinx_u55c_gen3x16_xdma_3_202210_1.xpfm
INTERFACE ?= 0

PLATFORM_NAME := $(strip $(patsubst %.xpfm,%,$(shell basename $(PLATFORM))))
XPLATFORM     := $(PLATFORM)

#--- Output directories ---
TEMP_DIR         := _x_temp.$(TARGET).$(PLATFORM_NAME)
BUILD_DIR        := build_dir.$(TARGET).$(PLATFORM_NAME)
BUILD_REPORT_DIR := $(CUR_DIR)/reports/_build.$(TARGET).$(PLATFORM_NAME)

#--- Tools ---
VPP    := v++
VIVADO := $(XILINX_VIVADO)/bin/vivado

#--- VPP flags ---
VPP_FLAGS   := -t $(TARGET) --platform $(XPLATFORM) --save-temps
VPP_FLAGS   += --vivado.prop run.impl_1.strategy=Performance_Auto_3

VPP_LDFLAGS := --optimize 2 -R 2
VPP_LDFLAGS += --config $(CUR_DIR)/conn_u55c_if0.tmp.cfg

#--- External repo for CMAC ---
VNX_REPO_URL := https://github.com/Xilinx/xup_vitis_network_example.git
VNX_REPO_DIR := $(CUR_DIR)/xup_vitis_network_example
CMAC_DIR     := $(VNX_REPO_DIR)/Ethernet
CMAC_XO      := $(CMAC_DIR)/_x.$(PLATFORM_NAME)/cmac_$(INTERFACE).xo

#--- Input .xo files ---
XO_DIR      := ./xo
KERNEL_OBJS := $(XO_DIR)/graphRandomWalk_0.xo $(CMAC_XO)

#--- Output xclbin ---
XCLBIN := $(BUILD_DIR)/VNx_graphRandomWalk.xclbin

# ============================================================
# Clone the VNx repo if not already present
# ============================================================
$(VNX_REPO_DIR):
	git clone --depth 1 $(VNX_REPO_URL) $(VNX_REPO_DIR)

# ============================================================
# Build CMAC XO from the external repo
# ============================================================
$(CMAC_XO): $(VNX_REPO_DIR)
	$(MAKE) -C $(CMAC_DIR) all DEVICE=$(DEVICE) INTERFACE=$(INTERFACE)

# ============================================================
# Build the RTL kernel XO from Vivado
# ============================================================
$(XO_DIR)/graphRandomWalk_0.xo: \
        ./src/xml/user_0.xml         \
        ./scripts/package_kernel.tcl \
        ./scripts/gen_xo.tcl         \
        ./src/IP/*.v
	mkdir -p $(XO_DIR)
	$(VIVADO) -mode batch -source scripts/gen_xo.tcl \
	    -tclargs $(XO_DIR)/graphRandomWalk_0.xo graphRandomWalk_0 $(TARGET) $(DEVICE) 0

# ============================================================
# Link XOs into the final xclbin
# ============================================================
$(XCLBIN): $(KERNEL_OBJS)
	mkdir -p $(BUILD_DIR) $(BUILD_REPORT_DIR)
	$(VPP)                    \
	    --vivado.synth.jobs 32 \
	    --vivado.impl.jobs  16 \
	    -l $(VPP_FLAGS)        \
	    --linkhook.do_last vpl.impl.opt_design,scripts/clear_drc_errors.tcl \
	    --temp_dir   $(TEMP_DIR)                             \
	    --report_dir $(BUILD_REPORT_DIR)/generator_collector \
	    $(VPP_LDFLAGS)         \
	    -o $@ $^

# ============================================================
# Convenience targets
# ============================================================
.PHONY: all clean cleanall

all: $(XCLBIN)

clean:
	-rm -rf $(BUILD_DIR) $(TEMP_DIR) *.log *.jou

cleanall: clean
	-rm -rf _x_temp.* build_dir.* $(CUR_DIR)/reports $(XO_DIR) $(VNX_REPO_DIR)