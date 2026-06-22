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
        ${CMAKE_CURRENT_SOURCE_DIR}/../common/include
        /beta/bradley/gbbs
        /beta/bradley/gbbs/bazel-gbbs/external/+git_repository+parlaylib
    )
    target_compile_definitions(BFS_xrt
        PUBLIC
        HOMEGROWN
        LONG
        AMORTIZEDPD
        USEMALLOC
        PARLAY_USE_STD_ALLOC
    )
endif()
