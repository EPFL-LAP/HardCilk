set(HC_TARGET_NAME "${project_template}")

if(HC_ENABLE_SYSTEMC_TARGETS)
    add_executable(${HC_TARGET_NAME}_systemc "src/systemc_main.cpp")
    target_compile_definitions(${HC_TARGET_NAME}_systemc PUBLIC ${HC_SYSTEMC_COMPILE_DEFINITIONS})
    target_link_libraries(${HC_TARGET_NAME}_systemc PUBLIC hardcilk::systemc_driver)
    target_include_directories(${HC_TARGET_NAME}_systemc PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)

    verilate(
        ${HC_TARGET_NAME}_systemc
        SYSTEMC
        SOURCES hdl/${HC_TARGET_NAME}.v
        VERILATOR_ARGS ${HC_VERILATOR_ARGS} -I${CMAKE_CURRENT_SOURCE_DIR}/hdl
    )

    hdlscw(
        TARGET ${HC_TARGET_NAME}_systemc
        HDLINFO hdl/${HC_TARGET_NAME}.hdlinfo.json
        ARGS -i sctlm
    )
endif()

if(HC_ENABLE_PCIE_TARGETS)
    add_executable(${HC_TARGET_NAME}_pcie "src/pcie_main.cpp")
    target_link_libraries(${HC_TARGET_NAME}_pcie PUBLIC hardcilk::pcie_driver)
    target_include_directories(${HC_TARGET_NAME}_pcie PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
endif()

# Alveo V80 (QDMA). The entry point is src/v80_main.cpp. A project without
# one gets its U55C src/pcie_main.cpp, compiled against the V80 shim
# memIO_pcie.h (PCIeMemory = QdmaMemory with the board's HBM/register bases).
if(HC_ENABLE_QDMA_TARGETS)
    if(EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/src/v80_main.cpp)
        add_executable(${HC_TARGET_NAME}_v80 "src/v80_main.cpp")
    elseif(EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/src/pcie_main.cpp)
        message(STATUS "${HC_TARGET_NAME}: no src/v80_main.cpp, building ${HC_TARGET_NAME}_v80 from src/pcie_main.cpp via the V80 shim")
        add_executable(${HC_TARGET_NAME}_v80 "src/pcie_main.cpp")
        target_include_directories(${HC_TARGET_NAME}_v80 BEFORE PRIVATE ${HC_V80_SHIM_INCLUDE_DIR})
    else()
        message(WARNING "${HC_TARGET_NAME}: neither src/v80_main.cpp nor src/pcie_main.cpp exists; no ${HC_TARGET_NAME}_v80 target")
    endif()
    if(TARGET ${HC_TARGET_NAME}_v80)
        target_link_libraries(${HC_TARGET_NAME}_v80 PUBLIC hardcilk::qdma_driver)
        target_include_directories(${HC_TARGET_NAME}_v80 PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
    endif()
endif()

set(AL_PATH "/beta/shahawy/mfpga_ws/alveoLink_ws/AlveoLink")

if(HC_ENABLE_XRT_TARGETS)
    add_executable(${HC_TARGET_NAME}_xrt 
        "src/xrt_main.cpp" 
        ${AL_PATH}/common/sw/src/xNativeFPGA.cpp
        ${AL_PATH}/network/roce_v2/sw/src/cmac.cpp
        ${AL_PATH}/network/roce_v2/sw/src/HiveNet.cpp
        )
    target_link_libraries(${HC_TARGET_NAME}_xrt PUBLIC hardcilk::xrt_driver uuid)
    target_include_directories(${HC_TARGET_NAME}_xrt 
        PUBLIC 
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${AL_PATH}/common/sw/include
        ${AL_PATH}/network/roce_v2/sw/include
        )
endif()

