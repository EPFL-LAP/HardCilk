# BFS host — single-FPGA, no CMAC/VNx. Overlaid onto the generated project,
# replacing the VNx-dependent project_template CMakeLists. The embedded
# LockServer is internal, so the host links only the XRT driver (no vnx object).

if(HC_ENABLE_XRT_TARGETS)
    add_executable(BFS_xrt
        "src/xrt_main.cpp"
    )
    target_link_libraries(BFS_xrt PUBLIC hardcilk::xrt_driver uuid)
    target_include_directories(BFS_xrt
        PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
    )
endif()
