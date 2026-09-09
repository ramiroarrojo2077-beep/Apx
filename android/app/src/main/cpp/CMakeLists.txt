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

# El juego de instrucciones con el que se compilan los núcleos de cálculo.
#
# Sin esto, ggml se compila para armv8-a pelado: multiplica los enteros de un
# modelo cuantizado de a uno, con NEON común. Pidiendo armv8.2-a con dotprod
# aparece SDOT, que hace cuatro productos y la suma en una sola instrucción, y
# con fp16 la aritmética de media precisión deja de pasar por float. Es, lejos,
# lo que más cambia la velocidad, y todo teléfono con un Cortex-A55 o A75 —o
# sea, cualquiera de 2018 en adelante— las tiene. Al que no, la app se lo avisa
# antes de cargar nada en vez de cerrarse de golpe (ver nativeFaltantes).
set(GGML_CPU_ARM_ARCH    "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

# Los núcleos de ARM para matrices cuantizadas. Eligen implementación mirando
# el procesador en tiempo de ejecución, así que en un teléfono nuevo usan i8mm
# —ocho productos por instrucción— y en uno viejo se quedan con lo que haya.
set(GGML_CPU_KLEIDIAI    ON  CACHE BOOL "" FORCE)

# Reordena los pesos Q4 a un formato que se lee de corrido. Cuesta un momento
# al abrir el modelo y se recupera en el primer párrafo.
set(GGML_CPU_REPACK      ON  CACHE BOOL "" FORCE)

# tinyBLAS: multiplicación de matrices por bloques, para cuando hay que leer
# muchos tokens de una (el prompt del sistema, un PDF adjunto).
set(GGML_LLAMAFILE       ON  CACHE BOOL "" FORCE)

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        b10855
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama)

add_library(rama_llama SHARED rama_llama.cpp)

target_compile_options(rama_llama PRIVATE -O3 -fvisibility=hidden)

# El puente no calcula nada, pero se descarta todo lo que no se usa para que el
# .so no cargue de más: con llama.cpp adentro ya pesa bastante.
target_link_options(rama_llama PRIVATE -Wl,--gc-sections -Wl,--as-needed)
target_link_libraries(rama_llama PRIVATE llama)
