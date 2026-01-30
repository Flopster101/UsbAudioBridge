#ifndef JAVA_AUDIOTRACK_ENGINE_H
#define JAVA_AUDIOTRACK_ENGINE_H

#include <jni.h>

#include "audio_common.h"

class JavaAudioTrackEngine : public AudioEngine {
    jclass serviceClass = nullptr;
    jmethodID midInit = nullptr;
    jmethodID midStart = nullptr;
    jmethodID midWrite = nullptr;
    jmethodID midStop = nullptr;
    jmethodID midRelease = nullptr;
    bool prepared = false;

    // Helper to get ENV for the current thread
    JNIEnv* getEnv();

public:
    bool open(int rate, int channelCount) override;
    void start() override;
    void write(const uint8_t* data, size_t sizeBytes) override;
    void stop() override;
    void close() override;
    int getBurstFrames() override;
};

#endif  // JAVA_AUDIOTRACK_ENGINE_H
