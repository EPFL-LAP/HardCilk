# Functional-accuracy test for the MaximalIndependentSet NGS primitive -- pure CPU, no Vitis.
#
#   make test     build and run the tester
#   make          build the tester binary only
#   make clean    remove build artifacts
#
# Override inputs with:
#   make test GRAPH=/path/to/graph.txt

HLS_INC ?= $(firstword $(wildcard \
  /beta/tools/Xilinx/Vitis_HLS/2022.1/include \
  /beta/tools/2025.1/Vitis/include \
  /alpha/tools/Xilinx/Vitis_HLS/*/include \
  $(XILINX_HLS)/include))

CXX      ?= g++
CXXFLAGS ?= -std=c++14 -O2 -w \
            -DHLS_STREAM_THREAD_SAFE -I$(HLS_INC) -I..
LDFLAGS  ?= -pthread

GRAPH      ?= /beta/bradley/Graphs/tinyGraph.txt
KERNEL_SRC := ../MaximalIndependentSet.cpp
SRCS       := tester.cpp $(KERNEL_SRC)
BIN        := tester

$(BIN): $(SRCS) ../util.h
	@if [ -z "$(HLS_INC)" ]; then \
	  echo "ERROR: no Vitis HLS include dir found. Set HLS_INC=/path/to/include"; \
	  exit 1; \
	fi
	$(CXX) $(CXXFLAGS) tester.cpp $(KERNEL_SRC) -o $(BIN) $(LDFLAGS)

.PHONY: test clean
test: $(BIN)
	./$(BIN) $(GRAPH)

clean:
	rm -f $(BIN)
