#ifndef AAUDIO_ENGINE_H
#define AAUDIO_ENGINE_H

#include <aaudio/AAudio.h>

#include <atomic>

#include "audio_common.h"

// --- AAudio Output Engine ---
class AAudioEngine : public AudioEngine {
    AAudioStream* stream = nullptr;
    int32_t burstFrames = 0;
    std::atomic<bool> disconnected{false};

public:
    bool isDisconnected() const { return disconnected.load(); }
    void setDisconnected();

    bool open(int rate, int channelCount) override;
    void start() override;
    void write(const uint8_t* data, size_t sizeBytes) override;
    void stop() override;
    void close() override;
    int getBurstFrames() override;
};

// --- AAudio Input Engine ---
class AAudioInputEngine : public AudioInputEngine {
    AAudioStream* stream = nullptr;
    int inputPreset = 6;

public:
    void setInputPreset(int preset) override { inputPreset = preset; }
    bool open(int rate, int channelCount) override;
    void start() override;
    size_t read(uint8_t* data, size_t sizeBytes) override;
    void stop() override;
    void close() override;
};

#endif  // AAUDIO_ENGINE_H
