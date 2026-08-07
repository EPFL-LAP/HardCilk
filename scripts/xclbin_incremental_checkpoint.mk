# Shared Vivado incremental implementation support for xclbin workspaces.
#
# Including Makefiles must define CUR_DIR, TARGET, PLATFORM_NAME, and TEMP_DIR.
# Set VPP_BASE_CONFIG before including this file when the v++ --config input is
# not SRC_CFG (for example, VNx Makefiles use a generated TMP_CFG).

HARDCILK_ROOT ?= $(abspath $(CUR_DIR)/../..)
VPP_BASE_CONFIG ?= $(SRC_CFG)

INCREMENTAL_CFG_SCRIPT ?= $(HARDCILK_ROOT)/scripts/xclbin_add_incremental_checkpoint_to_cfg.sh
INCREMENTAL_CHECKPOINT_DIR ?= $(CUR_DIR)/checkpoints/$(PLATFORM_NAME)
INCREMENTAL_CHECKPOINT_PATTERN ?= $(INCREMENTAL_CHECKPOINT_DIR)/level0_wrapper_routed_*.dcp
INCREMENTAL_LATEST_CHECKPOINT ?= $(INCREMENTAL_CHECKPOINT_DIR)/latest.dcp
INCREMENTAL_CFG ?= $(INCREMENTAL_CHECKPOINT_DIR)/incremental.$(TARGET).cfg
INCREMENTAL_CURRENT_ROUTED_CHECKPOINT ?= $(CUR_DIR)/$(TEMP_DIR)/link/vivado/vpl/prj/prj.runs/impl_1/level0_wrapper_routed.dcp

_INCREMENTAL_NEWEST_CHECKPOINT := $(lastword $(sort $(wildcard $(INCREMENTAL_CHECKPOINT_PATTERN))))
ifneq ($(wildcard $(INCREMENTAL_LATEST_CHECKPOINT)),)
_INCREMENTAL_SELECTED_CHECKPOINT := $(INCREMENTAL_LATEST_CHECKPOINT)
else
_INCREMENTAL_SELECTED_CHECKPOINT := $(_INCREMENTAL_NEWEST_CHECKPOINT)
endif
ifneq ($(strip $(INCREMENTAL_CHECKPOINT)),)
_INCREMENTAL_SELECTED_CHECKPOINT := $(INCREMENTAL_CHECKPOINT)
endif

VPP_CONFIG := $(VPP_BASE_CONFIG)

define PREPARE_VPP_CONFIG
	@:
endef

ifeq ($(TARGET),hw)
ifeq ($(strip $(DISABLE_INCREMENTAL)),)
ifneq ($(strip $(_INCREMENTAL_SELECTED_CHECKPOINT)),)
VPP_CONFIG := $(INCREMENTAL_CFG)
define PREPARE_VPP_CONFIG
	@bash "$(INCREMENTAL_CFG_SCRIPT)" "$(VPP_BASE_CONFIG)" "$(abspath $(_INCREMENTAL_SELECTED_CHECKPOINT))" "$(INCREMENTAL_CFG)"
	@echo "[incremental] Using Vivado checkpoint: $(abspath $(_INCREMENTAL_SELECTED_CHECKPOINT))"
endef
endif
endif
endif

VPP_CONFIG_FLAG = --config $(VPP_CONFIG)

define ARCHIVE_INCREMENTAL_CHECKPOINT
	@:
endef

ifeq ($(TARGET),hw)
define ARCHIVE_INCREMENTAL_CHECKPOINT
	@if [ -f "$(INCREMENTAL_CURRENT_ROUTED_CHECKPOINT)" ]; then \
	    mkdir -p "$(INCREMENTAL_CHECKPOINT_DIR)"; \
	    ts=$$(date +%Y%m%d_%H%M%S); \
	    dest="$(INCREMENTAL_CHECKPOINT_DIR)/level0_wrapper_routed_$$ts.dcp"; \
	    cp -f "$(INCREMENTAL_CURRENT_ROUTED_CHECKPOINT)" "$$dest"; \
	    ln -sfn "$$(basename "$$dest")" "$(INCREMENTAL_LATEST_CHECKPOINT)"; \
	    echo "[incremental] Archived Vivado checkpoint: $$dest"; \
	else \
	    echo "[incremental] No routed checkpoint found at $(INCREMENTAL_CURRENT_ROUTED_CHECKPOINT); skipping archive."; \
	fi
endef
endif
