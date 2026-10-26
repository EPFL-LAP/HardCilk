cmake_minimum_required(VERSION 3.16)
project(hardcilk_cpu_host NONE)
find_package(Python3 REQUIRED COMPONENTS Interpreter)
get_filename_component(CPU_PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
add_custom_target(cpu_host ALL
    COMMAND ${Python3_EXECUTABLE} "${CPU_PROJECT_ROOT}/scripts/project.py" host
    WORKING_DIRECTORY "${CPU_PROJECT_ROOT}" USES_TERMINAL)
