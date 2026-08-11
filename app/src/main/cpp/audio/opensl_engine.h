#ifndef OPENSL_ENGINE_H
#define OPENSL_ENGINE_H

#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>

#include <condition_variable>
#include <cstdint>
#include <atomic>
#include <mutex>
#include <vector>

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
    static constexpr int kQueueDepth = 4;
    int availableSlots = kQueueDepth;
    std::atomic<bool> stopped{false};
    std::vector<std::vector<uint8_t>> buffers_{kQueueDepth};
    std::vector<int64_t> enqueueSeq_{kQueueDepth, 0};
    int64_t enqueueCount_ = 0;
    int64_t completeCount_ = 0;

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
