# fullTriangleCountDecoupled host — single-FPGA, no CMAC/VNx. Links the XRT
# driver, and GBBS/parlay for the reference triangle count it validates and
# times against (same arrangement as the BFS host).

if(HC_ENABLE_XRT_TARGETS)
    add_executable(fullTriangleCountDecoupled_xrt
        "src/xrt_main.cpp"
    )
    target_link_libraries(fullTriangleCountDecoupled_xrt PUBLIC hardcilk::xrt_driver uuid)
    target_include_directories(fullTriangleCountDecoupled_xrt
        PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/../common/include
        /beta/bradley/gbbs
        /beta/bradley/gbbs/bazel-gbbs/external/+git_repository+parlaylib
    )
    target_compile_definitions(fullTriangleCountDecoupled_xrt
        PUBLIC
        HOMEGROWN
        LONG
        AMORTIZEDPD
        USEMALLOC
        PARLAY_USE_STD_ALLOC
    )
endif()
