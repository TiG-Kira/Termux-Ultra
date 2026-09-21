cmake_minimum_required(VERSION 4.1)
project("terminal")

set(CMAKE_C_STANDARD 23)
set(CMAKE_CXX_STANDARD 23)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_compile_options(
    -Wall -Wextra -Werror
    -O3
    -fno-stack-protector
    -flto
)

add_link_options(
    -Wl,--gc-sections
    -Wl,-s
    -flto
)

add_library(pty SHARED
    pty.cpp
)
