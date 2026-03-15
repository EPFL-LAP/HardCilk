set(HC_TARGET_NAME "${project_template}")

if(HC_ENABLE_XRT_TARGETS)

    execute_process(
        COMMAND git rev-parse --show-toplevel
        WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
        OUTPUT_VARIABLE HARDCILK_ROOT
        OUTPUT_STRIP_TRAILING_WHITESPACE
        RESULT_VARIABLE GIT_RESULT
    )
    if(NOT GIT_RESULT EQUAL 0)
        message(FATAL_ERROR "Could not determine HardCilk repository root. Ensure this project is inside the HardCilk git repository.")
    endif()

    if(NOT EXISTS "${HARDCILK_ROOT}/xup_vitis_network_example")
        message(FATAL_ERROR
            "xup_vitis_network_example not found under the HardCilk repository root (${HARDCILK_ROOT}).\n"
            "Please clone it by running:\n"
            "  git clone https://github.com/Xilinx/xup_vitis_network_example.git ${HARDCILK_ROOT}/xup_vitis_network_example"
        )
    endif()

    set(VNX_INCLUDE_PATH "${HARDCILK_ROOT}/xup_vitis_network_example/xrt_host_api/include")
    set(VNX_PATH "${HARDCILK_ROOT}/xup_vitis_network_example/xrt_host_api")
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


