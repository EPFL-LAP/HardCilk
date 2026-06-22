if(HC_ENABLE_XRT_TARGETS)
    add_executable(triangleCountDecoupled_xrt
        "src/xrt_main.cpp"
    )
    target_link_libraries(triangleCountDecoupled_xrt PUBLIC hardcilk::xrt_driver uuid)
    target_include_directories(triangleCountDecoupled_xrt
        PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/../common/include
    )
endif()
