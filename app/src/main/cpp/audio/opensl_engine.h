#ifndef OPENSL_ENGINE_H
#define OPENSL_ENGINE_H

#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>

#include <condition_variable>
#include <mutex>

#include "audio_common.h"

class OpenSLEngine : public AudioEngine {
    SLObjectItf engineObject = nullptr;
    SLEngineItf engineEngine = nullptr;
    SLObjectItf outputMixObject = nullptr;
    SLObjectItf playerObject = nullptr;
    SLPlayItf playerPlay = nullptr;
    SLAndroidSimpleBufferQueueItf playerBufferQueue = nullptr;

    std::mutex queueMutex;
    std::condition_variable queueCv;
    bool bufferReady = true;  // Simple flow control

    static void bqPlayerCallback(SLAndroidSimpleBufferQueueItf bq, void* context);

public:
    bool open(int rate, int channelCount) override;
    void start() override;
    void write(const uint8_t* data, size_t sizeBytes) override;
    void stop() override;
    void close() override;
    int getBurstFrames() override;
};

#endif  // OPENSL_ENGINE_H
