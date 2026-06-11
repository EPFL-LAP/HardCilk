# ─────────────────────────────────────────────────────────────────────────────
# Functional-accuracy test for the BFS kernels — pure CPU, no Vitis.
#
#   make test     build and run the tester against tinyGraph
#   make          build the tester binary only
#   make clean    remove build artifacts
#
# The tester links the real synthesizable code from ../BFS.cpp and only needs
# the Vitis HLS headers (ap_int.h, hls_stream.h, ap_axi_sdata.h) on the include
# path. Override the header location with:  make HLS_INC=/path/to/include
# ─────────────────────────────────────────────────────────────────────────────

# First existing HLS include dir wins; override on the command line if needed.
HLS_INC ?= $(firstword $(wildcard \
  /beta/tools/Xilinx/Vitis_HLS/2022.1/include \
  /beta/tools/2025.1/Vitis/include \
  /alpha/tools/Xilinx/Vitis_HLS/*/include \
  $(XILINX_HLS)/include))

CXX      ?= g++
# HLS_STREAM_THREAD_SAFE makes hls::stream a mutex/condvar FIFO so the lock
# responder can run on its own thread alongside the helper.
# -w silences the noisy Vitis HLS header warnings (sign-compare, unused, etc.).
CXXFLAGS ?= -std=c++14 -O2 -w \
            -DHLS_STREAM_THREAD_SAFE -I$(HLS_INC) -I..
LDFLAGS  ?= -pthread

GRAPH ?= /beta/bradley/Graphs/tinyGraph.txt

# MODEL selects which synthesizable kernel to link:
#   MODEL=old (default)  ->  ../BFS.cpp      (1 lock server, 3 m_axi ports)
#   MODEL=new            ->  ../BFS_new.cpp  (2 lock servers, 6 m_axi ports)
# The define BFS_NEW switches tester.cpp's helper declaration/call to match.
KERNEL_SRC := ../wBFS.cpp

SRCS = tester.cpp $(KERNEL_SRC)
BIN  = tester

$(BIN): $(SRCS) ../util.h
	@if [ -z "$(HLS_INC)" ]; then \
	  echo "ERROR: no Vitis HLS include dir found. Set HLS_INC=/path/to/include"; \
	  exit 1; \
	fi
	$(CXX) $(CXXFLAGS) $(DEFS) tester.cpp $(KERNEL_SRC) -o $(BIN) $(LDFLAGS)

.PHONY: test clean
test: $(BIN)
	./$(BIN) $(GRAPH)

clean:
	rm -f $(BIN)
