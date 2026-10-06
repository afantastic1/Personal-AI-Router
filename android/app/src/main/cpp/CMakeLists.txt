# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES.
# SPDX-License-Identifier: Apache-2.0

cmake_minimum_required(VERSION 3.22.1)
project(pair_mnn LANGUAGES CXX)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "PAIR MNN supports only arm64-v8a; got '${ANDROID_ABI}'.")
endif()

set(PAIR_MNN_RUNTIME_VARIANT "cpu" CACHE STRING "MNN runtime variant: cpu or opencl")
if(NOT PAIR_MNN_RUNTIME_VARIANT STREQUAL "cpu" AND NOT PAIR_MNN_RUNTIME_VARIANT STREQUAL "opencl")
    message(FATAL_ERROR "PAIR_MNN_RUNTIME_VARIANT must be 'cpu' or 'opencl'; got '${PAIR_MNN_RUNTIME_VARIANT}'.")
endif()

get_filename_component(MNN_ARTIFACT_ROOT
    "${CMAKE_CURRENT_LIST_DIR}/../../../../build/generated/mnn/arm64-v8a"
    ABSOLUTE
)
if(PAIR_MNN_RUNTIME_VARIANT STREQUAL "opencl")
    set(MNN_RUNTIME_DIR "${MNN_ARTIFACT_ROOT}/opencl")
    set(PAIR_MNN_HAS_OPENCL 1)
else()
    set(MNN_RUNTIME_DIR "${MNN_ARTIFACT_ROOT}")
    set(PAIR_MNN_HAS_OPENCL 0)
endif()
set(MNN_INCLUDE_DIR "${MNN_RUNTIME_DIR}/include")
if(PAIR_MNN_RUNTIME_VARIANT STREQUAL "opencl")
    set(MNN_INCLUDE_DIR "${MNN_ARTIFACT_ROOT}/include")
endif()
foreach(required_file IN ITEMS
        "${MNN_RUNTIME_DIR}/libMNN.so"
        "${MNN_RUNTIME_DIR}/libllm.so"
        "${MNN_INCLUDE_DIR}/MNN/MNNDefine.h"
        "${MNN_INCLUDE_DIR}/llm/llm.hpp")
    if(NOT EXISTS "${required_file}")
        message(FATAL_ERROR "Pinned MNN runtime artifact is missing: ${required_file}. Run :app:buildMnnRuntime first.")
    endif()
endforeach()
if(PAIR_MNN_HAS_OPENCL AND NOT EXISTS "${MNN_RUNTIME_DIR}/libMNN_CL.so")
    message(FATAL_ERROR "OpenCL MNN runtime is missing libMNN_CL.so; run :app:buildMnnRuntime with -PpairMnnOpenCL=true first.")
endif()

add_library(pair_mnn SHARED NativeMnn.cpp)
target_compile_features(pair_mnn PRIVATE cxx_std_17)
target_include_directories(pair_mnn SYSTEM PRIVATE "${MNN_INCLUDE_DIR}")
target_compile_options(pair_mnn PRIVATE -Wall -Wextra -Werror)
target_compile_definitions(pair_mnn PRIVATE PAIR_MNN_HAS_OPENCL=${PAIR_MNN_HAS_OPENCL})
target_link_options(pair_mnn PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
    "-Wl,--no-as-needed"
)

add_library(pair_mnn_llm SHARED IMPORTED GLOBAL)
set_target_properties(pair_mnn_llm PROPERTIES
    IMPORTED_LOCATION "${MNN_RUNTIME_DIR}/libllm.so"
    INTERFACE_INCLUDE_DIRECTORIES "${MNN_INCLUDE_DIR}"
)

add_library(pair_mnn_core SHARED IMPORTED GLOBAL)
set_target_properties(pair_mnn_core PROPERTIES
    IMPORTED_LOCATION "${MNN_RUNTIME_DIR}/libMNN.so"
    INTERFACE_INCLUDE_DIRECTORIES "${MNN_INCLUDE_DIR}"
)

find_library(android-log-lib log REQUIRED)
target_link_libraries(pair_mnn PRIVATE pair_mnn_llm pair_mnn_core "${android-log-lib}")
set_target_properties(pair_mnn PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${MNN_RUNTIME_DIR}"
)
