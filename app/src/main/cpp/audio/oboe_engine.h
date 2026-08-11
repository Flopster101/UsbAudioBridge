#ifndef OBOE_ENGINE_H
#define OBOE_ENGINE_H

#include <oboe/Oboe.h>

#include <atomic>
#include <memory>

#include "audio_common.h"

// Forward declaration so the error callback can reference the engine.
class OboeEngine;

// Callback object registered with Oboe so we get notified of stream disconnect.
class OboeErrorCallback : public oboe::AudioStreamErrorCallback {
public:
    void setEngine(OboeEngine* engine) { enginePtr = engine; }

    bool onError(oboe::AudioStream*, oboe::Result error) override;
    void onErrorBeforeClose(oboe::AudioStream*, oboe::Result error) override;

private:
    OboeEngine* enginePtr = nullptr;
};

// --- Oboe Output Engine ---
class OboeEngine : public AudioEngine {
    std::shared_ptr<oboe::AudioStream> stream;
    OboeErrorCallback errorCallback;
    int32_t burstFrames = 0;
    std::atomic<bool> disconnected{false};

public:
    bool isDisconnected() const { return disconnected.load(); }
    bool setDisconnected();

    bool open(int rate, int channelCount) override;
    void start() override;
    void write(const uint8_t* data, size_t sizeBytes) override;
    void stop() override;
    void close() override;
    int getBurstFrames() override;
};

#endif  // OBOE_ENGINE_H