# CMAKE MINIMUM REQUIRED VERSION
set(CMAKE_MINIMUM_REQUIRED_VERSION 3.0)
set(PROJECT_NAME "triangle")
project(${PROJECT_NAME})

#define build type
set(CMAKE_BUILD_TYPE Debug)

# define clang as the compiler and define its directory
set(CMAKE_C_COMPILER /opt/opencilk/bin/clang)
set(CMAKE_CXX_COMPILER /opt/opencilk/bin/clang++)

# degfine compilation flags

set(CMAKE_C_FLAGS "-fopencilk -O3")

# define the project name



# define sources

set(SOURCES
    "triangle.cpp"
)

# compile the project



add_executable(${PROJECT_NAME} ${SOURCES})

