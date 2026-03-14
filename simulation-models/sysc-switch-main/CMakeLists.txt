cmake_minimum_required(VERSION 3.28)
project(sysc_tb)

set(CMAKE_CXX_STANDARD 17)

add_compile_definitions(SC_DISABLE_API_VERSION_CHECK)
add_compile_definitions(SC_ALLOW_DEPRECATED_IEEE_API)
add_compile_definitions(SC_DEFAULT_WRITER_POLICY=SC_MANY_WRITERS)

find_package(SystemCLanguage REQUIRED)
find_package(fmt REQUIRED)

add_executable(sysc-switch src/main.cpp)
target_include_directories(sysc-switch PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(sysc-switch PUBLIC SystemC::systemc fmt::fmt)

# this is for later RTL-network co-simulation

find_package(verilator REQUIRED)
find_package(hdlscw REQUIRED)
# find_package(sctlm REQUIRED)

verilate(
    sysc-switch
    SYSTEMC
    SOURCES rtl/NetworkNode.sv

    # TODO --pins-bv 2 should be removed and
    # protocols should comply with the correct types
    VERILATOR_ARGS -I${CMAKE_CURRENT_SOURCE_DIR}/hdl --pins-bv 2
)

hdlscw(
    TARGET sysc-switch
    HDLINFO rtl/NetworkNode.hdlinfo.json
    ARGS -r ${CMAKE_CURRENT_SOURCE_DIR}/protocols.py
)
