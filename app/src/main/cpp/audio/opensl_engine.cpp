#include "opensl_engine.h"

#include <cstring>

#include "../logging/logging.h"

void OpenSLEngine::bqPlayerCallback(SLAndroidSimpleBufferQueueItf bq, void* context) {
    OpenSLEngine* engine = static_cast<OpenSLEngine*>(context);
    std::lock_guard<std::mutex> lock(engine->queueMutex);
    if (engine->availableSlots < kQueueDepth) {
        engine->availableSlots++;
    }
    engine->completeCount_++;
    engine->queueCv.notify_one();
}

bool OpenSLEngine::open(int rate, int channelCount) {
    SLresult result;
    // 1. Create Engine
    result = slCreateEngine(&engineObject, 0, NULL, 0, NULL, nullptr);
    if (result != SL_RESULT_SUCCESS) return false;
    result = (*engineObject)->Realize(engineObject, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) return false;
    result = (*engineObject)->GetInterface(engineObject, SL_IID_ENGINE, &engineEngine);
    if (result != SL_RESULT_SUCCESS) return false;

    // 2. Create Output Mix
    result = (*engineEngine)->CreateOutputMix(engineEngine, &outputMixObject, 0, 0, 0);
    if (result != SL_RESULT_SUCCESS) return false;
    result = (*outputMixObject)->Realize(outputMixObject, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) return false;

    // 3. Configure Audio Source
    SLDataLocator_AndroidSimpleBufferQueue loc_bufq = {SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE,
                                                       kQueueDepth};
    SLDataFormat_PCM format_pcm = {
        SL_DATAFORMAT_PCM,           (SLuint32)channelCount,
        (SLuint32)(rate * 1000),     SL_PCMSAMPLEFORMAT_FIXED_16,
        SL_PCMSAMPLEFORMAT_FIXED_16, SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT,
        SL_BYTEORDER_LITTLEENDIAN};
    SLDataSource audioSrc = {&loc_bufq, &format_pcm};

    // 4. Configure Audio Sink
    SLDataLocator_OutputMix loc_outmix = {SL_DATALOCATOR_OUTPUTMIX, outputMixObject};
    SLDataSink audioSnk = {&loc_outmix, NULL};

    // 5. Create Audio Player
    const SLInterfaceID ids[1] = {SL_IID_ANDROIDSIMPLEBUFFERQUEUE};
    const SLboolean req[1] = {SL_BOOLEAN_TRUE};
    result =
        (*engineEngine)
            ->CreateAudioPlayer(engineEngine, &playerObject, &audioSrc, &audioSnk, 1, ids, req);
    if (result != SL_RESULT_SUCCESS) {
        LOGE("OpenSL CreateAudioPlayer failed");
        return false;
    }

    result = (*playerObject)->Realize(playerObject, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) return false;

    result = (*playerObject)->GetInterface(playerObject, SL_IID_PLAY, &playerPlay);
    if (result != SL_RESULT_SUCCESS) return false;

    result = (*playerObject)
                 ->GetInterface(playerObject, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &playerBufferQueue);
    if (result != SL_RESULT_SUCCESS) return false;

    result = (*playerBufferQueue)->RegisterCallback(playerBufferQueue, bqPlayerCallback, this);
    if (result != SL_RESULT_SUCCESS) return false;

    return true;
}

void OpenSLEngine::start() {
    stopped.store(false);
    if (playerPlay) (*playerPlay)->SetPlayState(playerPlay, SL_PLAYSTATE_PLAYING);
}

void OpenSLEngine::write(const uint8_t* data, size_t sizeBytes) {
    if (!playerBufferQueue || sizeBytes < 4) return;

    std::unique_lock<std::mutex> lock(queueMutex);
    queueCv.wait(lock, [this] { return stopped.load() || availableSlots > 0; });
    if (stopped.load()) return;
    availableSlots--;

    int slot = -1;
    for (int i = 0; i < kQueueDepth; i++) {
        if (enqueueSeq_[i] <= completeCount_) {
            slot = i;
            break;
        }
    }
    if (slot < 0) {
        slot = 0;
    }

    if (buffers_[slot].size() < sizeBytes) {
        buffers_[slot].resize(sizeBytes);
    }
    std::memcpy(buffers_[slot].data(), data, sizeBytes);
    enqueueSeq_[slot] = ++enqueueCount_;

    SLresult result = (*playerBufferQueue)->Enqueue(playerBufferQueue, buffers_[slot].data(),
                                                    sizeBytes);
    if (result != SL_RESULT_SUCCESS) {
        static int enqueueErrorLogCount = 0;
        if ((enqueueErrorLogCount++ % 50) == 0) {
            LOGE("[Native] OpenSL enqueue failed: %d", result);
        }
        enqueueSeq_[slot] = 0;
        if (availableSlots < kQueueDepth) {
            availableSlots++;
        }
    }
}

void OpenSLEngine::stop() {
    if (playerPlay) (*playerPlay)->SetPlayState(playerPlay, SL_PLAYSTATE_STOPPED);
    if (playerBufferQueue) (*playerBufferQueue)->Clear(playerBufferQueue);
    {
        std::lock_guard<std::mutex> lock(queueMutex);
        availableSlots = kQueueDepth;
        completeCount_ = enqueueCount_;
    }
    stopped.store(true);
    queueCv.notify_all();
}

void OpenSLEngine::close() {
    if (playerObject) {
        (*playerObject)->Destroy(playerObject);
        playerObject = nullptr;
    }
    if (outputMixObject) {
        (*outputMixObject)->Destroy(outputMixObject);
        outputMixObject = nullptr;
    }
    if (engineObject) {
        (*engineObject)->Destroy(engineObject);
        engineObject = nullptr;
    }
}

int OpenSLEngine::getBurstFrames() { return 192; }  // Default approximate burst
