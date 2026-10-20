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


# Alveo V80: CPM5 QDMA through the qdma-pf character devices and the PF1 BAR2
# register window (driver/include/memIO_qdma.h). Plain POSIX, no svm/d4e.
if(HC_ENABLE_QDMA_TARGETS)
    find_package(Threads REQUIRED)

    add_library(
        hardcilk::qdma_driver
        INTERFACE IMPORTED GLOBAL
    )

    target_include_directories(
        hardcilk::qdma_driver
        INTERFACE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
    )

    target_link_libraries(
        hardcilk::qdma_driver
        INTERFACE
        Threads::Threads
    )

    target_sources(
        hardcilk::qdma_driver
        INTERFACE
        ${CMAKE_CURRENT_SOURCE_DIR}/src/hardCilkDriver.cpp
    )

    # Used by projects that have no src/v80_main.cpp: their pcie_main.cpp is
    # built with this directory first on the include path (see the project CMakeLists).
    set(HC_V80_SHIM_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/include/v80_shim CACHE INTERNAL "")

    # Stand-alone bring-up check: HBM and register access, no HardCilk protocol.
    add_executable(hc_v80_probe ${CMAKE_CURRENT_SOURCE_DIR}/tools/hc_v80_probe.cpp)
    target_include_directories(hc_v80_probe PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
    target_link_libraries(hc_v80_probe PRIVATE Threads::Threads)
endif()
