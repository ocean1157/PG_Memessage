cmake_minimum_required(VERSION 3.20)

project(PGBugMailTracker VERSION 1.0.0 LANGUAGES C)

function(configure_pg_bug_target target_name)
    target_compile_definitions(${target_name} PRIVATE
        UNICODE
        _UNICODE
        WINVER=0x0601
        _WIN32_WINNT=0x0601
    )

    target_compile_features(${target_name} PRIVATE c_std_11)

    if(MSVC)
        target_compile_options(${target_name} PRIVATE /W4 /utf-8)
    else()
        target_compile_options(${target_name} PRIVATE -Wall -Wextra -municode -mwindows)
        target_link_options(${target_name} PRIVATE -municode -mwindows)
    endif()

    set_target_properties(${target_name} PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
    )
endfunction()

add_executable(PGBugMailTracker WIN32
    native-c/java_launcher.c
    native-c/pg_bug_tracker.rc
)
target_link_libraries(PGBugMailTracker PRIVATE shell32)
configure_pg_bug_target(PGBugMailTracker)
