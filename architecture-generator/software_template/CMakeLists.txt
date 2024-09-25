cmake_minimum_required(VERSION 3.22)
set(CMAKE_CXX_STANDARD 20)

project(hardcilk)

set(HC_VERILATOR_ARGS --no-timing --timescale 1ns/1ps -Wno-WIDTHEXPAND -Wno-STMTDLY -Wno-WIDTHTRUNC -Wno-WIDTHXZEXPAND -Wno-ASCRANGE -Wno-CASEINCOMPLETE -Wno-COMBDLY -Wno-PINMISSING --trace)
set(HC_SYSTEMC_COMPILE_DEFINITIONS VERILATED_TRACE_ENABLED)

# add more variables
# maybe make these variables overridable

if(NOT DEFINED HC_ENABLE_SYSTEMC_TARGETS)
    set(
        HC_ENABLE_SYSTEMC_TARGETS ON
        CACHE BOOL
        "Enables SystemC targets, requires sctlm, verilator, and SystemC."
    )
endif()

if(NOT DEFINED HC_ENABLE_PCIE_TARGETS)
    set(
        HC_ENABLE_PCIE_TARGETS ON
        CACHE BOOL
        "Enables SystemC targets, requires sctlm, verilator, and SystemC."
    )
endif()

if (NOT DEFINED HC_NO_FMT)
    set(
        HC_NO_FMT OFF
        CACHE BOOL
        "Enables targets that use the format library, for convenience."
    )
endif()

if (HC_ENABLE_SYSTEMC_TARGETS)
    find_package(SystemCLanguage REQUIRED)
    find_package(verilator REQUIRED)
    find_package(hdlscw REQUIRED)
    find_package(sctlm REQUIRED)
endif()

if (NOT HC_NO_FMT)
    find_package(fmt REQUIRED)
endif()

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/driver)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/svm/software)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/projects)
