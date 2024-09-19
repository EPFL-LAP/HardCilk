set(HC_TARGET_NAME "fibonacci")

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
