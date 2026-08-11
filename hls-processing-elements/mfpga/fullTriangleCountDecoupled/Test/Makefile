# Functional-accuracy test for the fullTriangleCountDecoupled kernels -- pure
# CPU, no Vitis.
#
#   make test     build and run the tester against the CPU golden
#   make gbbs     same, and cross-check the triangle count against GBBS
#   make          build the tester binary only
#   make clean    remove build artifacts
#
# Override inputs with:
#   make test GRAPH=/path/to/edges.txt WATCHDOG=1000000
#
# The graph is read as an undirected edge list; self loops and duplicate edges
# are dropped, and each adjacency list is sorted, because the adder merges them.

HLS_INC ?= $(firstword $(wildcard \
  /beta/tools/Xilinx/Vitis_HLS/2022.1/include \
  /beta/tools/2025.1/Vitis/include \
  /alpha/tools/Xilinx/Vitis_HLS/*/include \
  $(XILINX_HLS)/include))

GBBS_DIR    ?= /beta/bradley/gbbs
PARLAY_DIR  ?= $(GBBS_DIR)/bazel-gbbs/external/+git_repository+parlaylib

CXX      ?= g++
CXXFLAGS ?= -std=c++14 -O2 -w \
            -DHLS_STREAM_THREAD_SAFE -I$(HLS_INC) -I..
LDFLAGS  ?= -pthread

GRAPH    ?= /beta/bradley/Graphs/tinyGraph.txt
WATCHDOG ?=

KERNEL_SRC := ../fullTriangleCountDecoupled.cpp
BIN        := tester
BIN_GBBS   := tester_gbbs

$(BIN): tester.cpp $(KERNEL_SRC) ../util.h
	@if [ -z "$(HLS_INC)" ]; then \
	  echo "ERROR: no Vitis HLS include dir found. Set HLS_INC=/path/to/include"; \
	  exit 1; \
	fi
	$(CXX) $(CXXFLAGS) tester.cpp $(KERNEL_SRC) -o $(BIN) $(LDFLAGS)

# The GBBS reference is a separate translation unit: it needs C++17 and parlay,
# and the kernels need the HLS headers. Only the harness sees both, through one
# plain-CSR declaration.
tester_gbbs.o: tester.cpp $(KERNEL_SRC) ../util.h
	$(CXX) $(CXXFLAGS) -DUSE_GBBS -c tester.cpp -o tester_gbbs.o

kernels_gbbs.o: $(KERNEL_SRC) ../util.h
	$(CXX) $(CXXFLAGS) -c $(KERNEL_SRC) -o kernels_gbbs.o

gbbs_reference.o: gbbs_reference.cpp
	$(CXX) -std=c++17 -O2 -w -I$(GBBS_DIR) -I$(PARLAY_DIR) \
	  -c gbbs_reference.cpp -o gbbs_reference.o

$(BIN_GBBS): tester_gbbs.o kernels_gbbs.o gbbs_reference.o
	$(CXX) tester_gbbs.o kernels_gbbs.o gbbs_reference.o -o $(BIN_GBBS) $(LDFLAGS)

.PHONY: test gbbs clean
test: $(BIN)
	./$(BIN) $(GRAPH) $(WATCHDOG)

gbbs: $(BIN_GBBS)
	./$(BIN_GBBS) $(GRAPH) $(WATCHDOG)

clean:
	rm -f $(BIN) $(BIN_GBBS) tester_gbbs.o kernels_gbbs.o gbbs_reference.o
