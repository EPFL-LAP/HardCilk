set(HC_TARGET_NAME "graphRandomWalk")

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


if(HC_ENABLE_XRT_TARGETS)

    set(VNX_INCLUDE_PATH "/beta/shahawy/mfpga_ws/VNx/xup_vitis_network_example/xrt_host_api/include")
    set(VNX_PATH "/beta/shahawy/mfpga_ws/VNx/xup_vitis_network_example/xrt_host_api")
    find_package(jsoncpp)


    if (jsoncpp_FOUND)
        message(STATUS "jsoncpp found on the system, compiling vnx with mac address utilities.")

        add_library(vnx OBJECT ${VNX_PATH}/src/cmac.cpp ${VNX_PATH}/src/networklayer.cpp ${VNX_PATH}/src/mac.cpp)
        target_link_libraries(vnx PRIVATE jsoncpp_lib)
        target_link_libraries(
            vnx
            INTERFACE
            /opt/xilinx/xrt/lib/libxrt++.so
            /opt/xilinx/xrt/lib/libxrt_core.so
            /opt/xilinx/xrt/lib/libxrt_coreutil.so
        )
        get_target_property(JSON_INC_PATH jsoncpp_lib INTERFACE_INCLUDE_DIRECTORIES)
        target_include_directories(vnx PUBLIC ${JSON_INC_PATH})
        target_include_directories(vnx PUBLIC /opt/xilinx/xrt/include)


    else (jsoncpp_FOUND)

        message(WARNING "jsoncpp not found on the system, compiling vnx without mac address utilities.")
        add_library(vnx OBJECT ${VNX_PATH}/src/cmac.cpp ${VNX_PATH}/src/networklayer.cpp)

    endif (jsoncpp_FOUND)

    target_include_directories(vnx PUBLIC ${VNX_INCLUDE_PATH})

    add_executable(${HC_TARGET_NAME}_xrt 
        "src/xrt_main.cpp" 
        )
    target_link_libraries(${HC_TARGET_NAME}_xrt PUBLIC hardcilk::xrt_driver uuid vnx)
    target_include_directories(${HC_TARGET_NAME}_xrt 
        PUBLIC 
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${VNX_INCLUDE_PATH}
        )
endif()

