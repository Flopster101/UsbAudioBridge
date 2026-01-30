#include "opensl_engine.h"

#include "../logging/logging.h"

void OpenSLEngine::bqPlayerCallback(SLAndroidSimpleBufferQueueItf bq, void* context) {
    OpenSLEngine* engine = static_cast<OpenSLEngine*>(context);
    std::lock_guard<std::mutex> lock(engine->queueMutex);
    engine->bufferReady = true;
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
    SLDataLocator_AndroidSimpleBufferQueue loc_bufq = {SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 2};
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
    if (playerPlay) (*playerPlay)->SetPlayState(playerPlay, SL_PLAYSTATE_PLAYING);
}

void OpenSLEngine::write(const uint8_t* data, size_t sizeBytes) {
    if (!playerBufferQueue) return;

    // Wait for buffer slot
    {
        std::unique_lock<std::mutex> lock(queueMutex);
        queueCv.wait_for(lock, std::chrono::milliseconds(100), [this] { return bufferReady; });
        bufferReady = false;
    }

    (*playerBufferQueue)->Enqueue(playerBufferQueue, data, sizeBytes);
}

void OpenSLEngine::stop() {
    if (playerPlay) (*playerPlay)->SetPlayState(playerPlay, SL_PLAYSTATE_STOPPED);
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
