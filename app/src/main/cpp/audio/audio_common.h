#ifndef AUDIO_COMMON_H
#define AUDIO_COMMON_H

#include <cstddef>
#include <cstdint>

// --- Audio Output Engine Interface ---
class AudioEngine {
public:
    virtual ~AudioEngine() = default;
    virtual bool open(int rate, int channelCount) = 0;
    virtual void start() = 0;
    virtual void write(const uint8_t* data, size_t sizeBytes) = 0;
    virtual void stop() = 0;
    virtual void close() = 0;
    virtual int getBurstFrames() = 0;
};

// --- Audio Input Engine Interface (For Mic) ---
class AudioInputEngine {
public:
    virtual ~AudioInputEngine() = default;
    virtual bool open(int rate, int channelCount) = 0;
    virtual void start() = 0;
    virtual size_t read(uint8_t* data, size_t sizeBytes) = 0;
    virtual void stop() = 0;
    virtual void close() = 0;
    virtual void setInputPreset(int preset) {}
};

#endif  // AUDIO_COMMON_H
