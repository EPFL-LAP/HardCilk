cmake_minimum_required(VERSION 3.22)
project(MyProject C CXX)

# Set the C++ standard
set(CMAKE_CXX_STANDARD 11)

# Locate all source files
file(GLOB SOURCES "src/*.cpp" "include/*.h" "include/*.tpp")

# Add executable target
add_executable(HardCilkDriver ${SOURCES})

# Include directories
include_directories(include/)

# Add the subdirectory containing the CMakeLists.txt file for the library
add_subdirectory(svm/software/error)
add_subdirectory(svm/software/util)
add_subdirectory(svm/software/d4e)
add_subdirectory(svm/software/com9n)

target_link_libraries(HardCilkDriver PUBLIC svm::d4e)