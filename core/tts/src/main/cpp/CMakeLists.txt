cmake_minimum_required(VERSION 3.22.1)
project(sea_g2p_jni LANGUAGES CXX)

set(SEA_G2P_LIB "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libsea_g2p_rs.so")

if(ANDROID_ABI STREQUAL "arm64-v8a" AND EXISTS "${SEA_G2P_LIB}")
    add_library(sea_g2p_rs SHARED IMPORTED)
    set_target_properties(
        sea_g2p_rs
        PROPERTIES
            IMPORTED_LOCATION "${SEA_G2P_LIB}"
            INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_CURRENT_SOURCE_DIR}/include"
    )

    add_library(sea_g2p_jni SHARED sea_g2p_jni.cpp)
    target_include_directories(sea_g2p_jni PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/include")
    target_link_libraries(sea_g2p_jni PRIVATE sea_g2p_rs log)
else()
    message(STATUS "sea_g2p_jni bị bỏ qua cho ABI không hỗ trợ: ${ANDROID_ABI}")
endif()
