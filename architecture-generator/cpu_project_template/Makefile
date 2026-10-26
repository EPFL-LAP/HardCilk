PYTHON ?= python3
.DEFAULT_GOAL := all
.PHONY: all firmware rtl host sim clean help
all: host
firmware rtl host sim clean:
	$(PYTHON) scripts/project.py $@
help:
	@echo 'make firmware | rtl | host | sim | clean'
	@echo 'CPU_REPO, HARDCILK_REPO, HDLSTUFF_PREFIX, RISCV_CC, RISCV_OBJCOPY, APP, SEEDS and JOBS may be overridden.'
