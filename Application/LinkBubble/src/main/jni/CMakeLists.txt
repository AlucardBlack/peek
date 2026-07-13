cmake_minimum_required(VERSION 3.22.1)
project(linkbubble-adblock)

set(CMAKE_CXX_STANDARD 14)

add_library(Peek SHARED
    main.cpp
    ABPFilterParser.cpp
    cosmeticFilter.cpp
    filter.cpp
    BloomFilter.cpp
    hashFn.cpp
    HashSet.cpp
)

target_include_directories(Peek PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(log-lib log)

target_link_libraries(Peek ${log-lib})
