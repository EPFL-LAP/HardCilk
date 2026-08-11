# Regular triangleCount host — single FPGA, no CMAC/VNx.  GBBS/parlay is used
# for the official reference count and the execution-time comparison.

if(HC_ENABLE_XRT_TARGETS)
    add_executable(triangleCount_xrt
        "src/xrt_main.cpp"
    )
    target_link_libraries(triangleCount_xrt PUBLIC hardcilk::xrt_driver uuid)
    target_include_directories(triangleCount_xrt
        PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/../common/include
        /beta/bradley/gbbs
        /beta/bradley/gbbs/bazel-gbbs/external/+git_repository+parlaylib
    )
    target_compile_definitions(triangleCount_xrt
        PUBLIC
        HOMEGROWN
        LONG
        AMORTIZEDPD
        USEMALLOC
        PARLAY_USE_STD_ALLOC
    )
endif()
