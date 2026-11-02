cmake_minimum_required(VERSION 3.22.1)
project(clawmate_meeting_complex_math C)

if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "The meeting dependency currently supports Android arm64-v8a only")
endif()

# Check all vendored inputs before compiling. No source download or binary
# rewriting is performed by the build.
file(READ "${CMAKE_CURRENT_SOURCE_DIR}/source-lock.json" source_lock)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/source-lock.json")
string(JSON file_count LENGTH "${source_lock}" files)
math(EXPR last_file "${file_count} - 1")
set(complex_sources)
foreach(index RANGE ${last_file})
    string(JSON source_path GET "${source_lock}" files ${index} path)
    string(JSON expected_hash GET "${source_lock}" files ${index} sha256)
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
        "${CMAKE_CURRENT_SOURCE_DIR}/${source_path}")
    file(SHA256 "${CMAKE_CURRENT_SOURCE_DIR}/${source_path}" actual_hash)
    if(NOT actual_hash STREQUAL expected_hash)
        message(FATAL_ERROR "Pinned complex-math source changed: ${source_path}")
    endif()
    if(source_path MATCHES "\\.c$")
        list(APPEND complex_sources "${source_path}")
    endif()
endforeach()

add_library(android-complex-math SHARED ${complex_sources})
target_compile_definitions(android-complex-math PRIVATE __USE_GNU)
target_compile_options(android-complex-math PRIVATE -fno-builtin)
target_link_libraries(android-complex-math PRIVATE m)
target_link_options(android-complex-math PRIVATE
    "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384"
    "-Wl,--as-needed" "-Wl,--no-undefined" "-Wl,-z,relro" "-Wl,-z,now")
