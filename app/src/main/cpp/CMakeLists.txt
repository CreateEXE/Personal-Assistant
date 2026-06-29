cmake_minimum_required(VERSION 3.22.1)

project("llama_jni")

add_library(
        llama_jni
        SHARED
        llama-jni.cpp)

target_link_libraries(
        llama_jni
        android
        log)
