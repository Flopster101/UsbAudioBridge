cmake_minimum_required(VERSION 3.22.1)

project("usbaudio")

# Build TinyALSA from source (Submodule)
# tinyalsa has a simple structure: src/pcm.c, src/mixer.c
add_library(tinyalsa STATIC
    ${CMAKE_SOURCE_DIR}/tinyalsa/src/pcm.c
    ${CMAKE_SOURCE_DIR}/tinyalsa/src/mixer.c
    ${CMAKE_SOURCE_DIR}/tinyalsa/src/limits.c
    ${CMAKE_SOURCE_DIR}/tinyalsa/src/pcm_hw.c
    ${CMAKE_SOURCE_DIR}/tinyalsa/src/pcm_plugin.c
    ${CMAKE_SOURCE_DIR}/tinyalsa/src/mixer_hw.c
    ${CMAKE_SOURCE_DIR}/tinyalsa/src/mixer_plugin.c
    ${CMAKE_SOURCE_DIR}/tinyalsa/src/snd_card_plugin.c
)

target_include_directories(tinyalsa PUBLIC
    ${CMAKE_SOURCE_DIR}/tinyalsa/include)

# Define our JNI library
add_library(usbaudio SHARED 
    native-lib.cpp
    logging/logging.cpp
    audio/aaudio_engine.cpp
    audio/opensl_engine.cpp
    audio/java_audio_track_engine.cpp
    core/bridge.cpp
)

# Link libraries
find_library(log-lib log)
find_library(aaudio-lib aaudio)

target_link_libraries(usbaudio
    tinyalsa
    ${log-lib}
    ${aaudio-lib}
    OpenSLES)
