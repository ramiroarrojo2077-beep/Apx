cmake_minimum_required(VERSION 3.22.1)
project(rama_llama CXX)

include(FetchContent)

# llama.cpp fijado a una versión concreta: la API cambia seguido y el puente
# de rama_llama.cpp está escrito contra ésta.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)  # Android no trae libomp
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)  # todo dentro de un solo .so

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        b10855
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama)

add_library(rama_llama SHARED rama_llama.cpp)

target_compile_options(rama_llama PRIVATE -O3 -fvisibility=hidden)
target_link_libraries(rama_llama PRIVATE llama)
