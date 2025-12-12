if(HC_ENABLE_SYSTEMC_TARGETS)
    add_library(
        hardcilk::systemc_driver
        INTERFACE IMPORTED GLOBAL
    )

    target_include_directories(
        hardcilk::systemc_driver
        INTERFACE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
    )

    target_link_libraries(
        hardcilk::systemc_driver
        INTERFACE
        sctlm::sctlm
    )

    target_sources(
        hardcilk::systemc_driver
        INTERFACE
        ${CMAKE_CURRENT_SOURCE_DIR}/src/hardCilkDriver.cpp
    )
endif()

if(HC_ENABLE_PCIE_TARGETS)
    add_library(
        hardcilk::pcie_driver
        INTERFACE IMPORTED GLOBAL
    )

    target_include_directories(
        hardcilk::pcie_driver
        INTERFACE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
    )

    target_link_libraries(
        hardcilk::pcie_driver
        INTERFACE
        svm::d4e
    )

    target_sources(
        hardcilk::pcie_driver
        INTERFACE
        ${CMAKE_CURRENT_SOURCE_DIR}/src/hardCilkDriver.cpp
    )
endif()

if(HC_ENABLE_XRT_TARGETS)
    add_library(
        hardcilk::xrt_driver
        INTERFACE IMPORTED GLOBAL
    )
    target_include_directories(
        hardcilk::xrt_driver
        INTERFACE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        /opt/xilinx/xrt/include   
    )
    target_link_libraries(
        hardcilk::xrt_driver
        INTERFACE
        /opt/xilinx/xrt/lib/libxrt++.so
        /opt/xilinx/xrt/lib/libxrt_core.so
        /opt/xilinx/xrt/lib/libxrt_coreutil.so
    )
    target_sources(
        hardcilk::xrt_driver
        INTERFACE
        ${CMAKE_CURRENT_SOURCE_DIR}/src/hardCilkDriver.cpp
    )
endif()