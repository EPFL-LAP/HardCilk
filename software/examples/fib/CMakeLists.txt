


cmake_minimum_required(VERSION 3.22)
project(MyProject C CXX)

# Set the C++ standard
set(CMAKE_CXX_STANDARD 11)

# create a variable for the svm/software directory
set(SVM_SOFTWARE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/svm/software)

# Locate all source files
file(GLOB SOURCES_SYSTEMC "src/hardCilkDriver.cpp" "include/*.h" "include/*.tpp" "hdl/*.sv" "hdl/*.v")
file(GLOB SOURCES_PCIE "src/hardCilkDriver.cpp" "include/hardCilkDriver.h" "include/FullSysGenDescriptor.h" "include/memIO_pcie.h" "include/fibonacciDriver.h" "include/*.tpp")

find_package(SystemCLanguage REQUIRED)
find_package(fmt REQUIRED)
find_package(verilator REQUIRED)

find_package(hdlscw REQUIRED)
find_package(sctlm REQUIRED)
# Add executable target
add_executable(fibonacciPcieDriver ${SOURCES_PCIE} "src/pcie_main.cpp")
add_executable(fibonacciSysCDriver ${SOURCES_SYSTEMC} "src/systemc_main.cpp")

target_compile_definitions(fibonacciSysCDriver PUBLIC VERILATED_TRACE_ENABLED)

# Include directories
include_directories(include/)

# Add the subdirectory containing the CMakeLists.txt file for the library
add_subdirectory(${SVM_SOFTWARE_DIR}/error)
add_subdirectory(${SVM_SOFTWARE_DIR}/util)
add_subdirectory(${SVM_SOFTWARE_DIR}/d4e)
add_subdirectory(${SVM_SOFTWARE_DIR}/com9n)

target_link_libraries(fibonacciSysCDriver PUBLIC svm::d4e sctlm::sctlm)
target_link_libraries(fibonacciPcieDriver PUBLIC svm::d4e) 

verilate(
    fibonacciSysCDriver
    SYSTEMC
    SOURCES hdl/fibonacci.sv
    VERILATOR_ARGS --no-timing  --timescale 1ns/1ps -I${CMAKE_CURRENT_SOURCE_DIR}/hdl -Wno-WIDTHEXPAND -Wno-STMTDLY -Wno-WIDTHTRUNC -Wno-WIDTHXZEXPAND -Wno-ASCRANGE -Wno-CASEINCOMPLETE -Wno-COMBDLY -Wno-PINMISSING --trace
)


hdlscw(
    test
    TARGET fibonacciSysCDriver 
    HDLINFO hdl/fibonacci.hdlinfo.json
    ARGS -i sctlm
)