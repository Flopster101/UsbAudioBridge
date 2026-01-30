#include "aaudio_engine.h"

#include <aaudio/AAudio.h>

#include "../logging/logging.h"

// Forward declaration for error callback
static void aaudioErrorCallback(AAudioStream* stream, void* userData, aaudio_result_t error);

// --- AAudio Output Engine ---

void AAudioEngine::setDisconnected() {
    disconnected.store(true);
    LOGD("[Native] AAudio stream marked as disconnected");
}

bool AAudioEngine::open(int rate, int channelCount) {
    AAudioStreamBuilder* builder;
    AAudio_createStreamBuilder(&builder);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setSampleRate(builder, rate);
    AAudioStreamBuilder_setChannelCount(builder, channelCount);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setErrorCallback(builder, aaudioErrorCallback, this);

    if (AAudioStreamBuilder_openStream(builder, &stream) != AAUDIO_OK) {
        LOGE("[Native] AAudio open failed");
        AAudioStreamBuilder_delete(builder);
        return false;
    }
    AAudioStreamBuilder_delete(builder);
    burstFrames = AAudioStream_getFramesPerBurst(stream);
    return true;
}

void AAudioEngine::start() {
    if (stream) AAudioStream_requestStart(stream);
}

void AAudioEngine::write(const uint8_t* data, size_t sizeBytes) {
    if (stream) {
        // timeout 100ms
        AAudioStream_write(stream, data, sizeBytes / 4,
                           100000000);  // divide by 2 for int16 frames (stereo? 4 bytes)
    }
}

void AAudioEngine::stop() {
    if (stream) AAudioStream_requestStop(stream);
}

void AAudioEngine::close() {
    if (stream) {
        AAudioStream_close(stream);
        stream = nullptr;
    }
}

int AAudioEngine::getBurstFrames() { return burstFrames; }

// --- AAudio Input Engine ---

bool AAudioInputEngine::open(int rate, int channelCount) {
    AAudioStreamBuilder* builder;
    AAudio_createStreamBuilder(&builder);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setSampleRate(builder, rate);
    AAudioStreamBuilder_setChannelCount(builder, channelCount);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setInputPreset(builder, (aaudio_input_preset_t)inputPreset);
    // Error callback handling for disconnect? For now simple.

    if (AAudioStreamBuilder_openStream(builder, &stream) != AAUDIO_OK) {
        LOGE("[Native] AAudio Input open failed");
        AAudioStreamBuilder_delete(builder);
        return false;
    }
    AAudioStreamBuilder_delete(builder);
    return true;
}

void AAudioInputEngine::start() {
    if (stream) AAudioStream_requestStart(stream);
}

size_t AAudioInputEngine::read(uint8_t* data, size_t sizeBytes) {
    if (!stream) return 0;
    // Read with timeout
    auto result = AAudioStream_read(stream, data, sizeBytes / 4, 100000000);
    // AAudio read size is in Frames. 1 Frame = 2 chars * 2 ch = 4 bytes.
    // sizeBytes is bytes.
    // frames = sizeBytes / 4.
    if (result < 0) return 0;
    return result * 4;  // Return bytes read
}

void AAudioInputEngine::stop() {
    if (stream) AAudioStream_requestStop(stream);
}

void AAudioInputEngine::close() {
    if (stream) {
        AAudioStream_close(stream);
        stream = nullptr;
    }
}

// AAudio error callback implementation
static void aaudioErrorCallback(AAudioStream* stream, void* userData, aaudio_result_t error) {
    AAudioEngine* engine = static_cast<AAudioEngine*>(userData);
    if (error == AAUDIO_ERROR_DISCONNECTED) {
        LOGD("[Native] AAudio error callback: Output disconnected");
        if (engine) {
            engine->setDisconnected();
        }
        reportOutputDisconnectToJava();
    } else {
        LOGE("[Native] AAudio error callback: error %d", error);
    }
}
