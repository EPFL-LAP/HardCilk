file(
    GLOB ALL_DIRECTORIES
    CONFIGURE_DEPENDS
    RELATIVE ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/*/
)

foreach(DIR ${ALL_DIRECTORIES})
    if(IS_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/${DIR}")
        add_subdirectory("${DIR}")
    endif()
endforeach()
