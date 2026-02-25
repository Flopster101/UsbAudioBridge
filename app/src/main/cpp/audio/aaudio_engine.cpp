#include "aaudio_engine.h"

#include <aaudio/AAudio.h>
#include <algorithm>
#include <dlfcn.h>

#include "../logging/logging.h"

// Forward declaration for error callback
static void aaudioErrorCallback(AAudioStream* stream, void* userData, aaudio_result_t error);

namespace {
using SetInputPresetFn = void (*)(AAudioStreamBuilder*, aaudio_input_preset_t);

SetInputPresetFn resolveSetInputPresetFn() {
    // Keep the library loaded for process lifetime once resolved.
    static void* handle = dlopen("libaaudio.so", RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        return nullptr;
    }
    return reinterpret_cast<SetInputPresetFn>(dlsym(handle, "AAudioStreamBuilder_setInputPreset"));
}

void maybeSetInputPreset(AAudioStreamBuilder* builder, int inputPreset) {
    static SetInputPresetFn setInputPresetFn = resolveSetInputPresetFn();
    if (setInputPresetFn) {
        setInputPresetFn(builder, static_cast<aaudio_input_preset_t>(inputPreset));
    }
}
}  // namespace

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
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
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
    if (burstFrames <= 0) {
        burstFrames = 192;
    }

    int32_t capacityFrames = AAudioStream_getBufferCapacityInFrames(stream);
    if (capacityFrames > 0) {
        int32_t targetFrames = std::max(burstFrames * 4, (capacityFrames * 3) / 4);
        targetFrames = std::min(targetFrames, capacityFrames);
        aaudio_result_t setFrames = AAudioStream_setBufferSizeInFrames(stream, targetFrames);
        if (setFrames > 0) {
            LOGD("[Native] AAudio buffer frames: burst=%d capacity=%d target=%d actual=%d",
                 burstFrames, capacityFrames, targetFrames, setFrames);
        }
    }
    return true;
}

void AAudioEngine::start() {
    if (stream) AAudioStream_requestStart(stream);
}

void AAudioEngine::write(const uint8_t* data, size_t sizeBytes) {
    if (!stream || sizeBytes < 4) return;

    int32_t totalFrames = static_cast<int32_t>(sizeBytes / 4);
    int32_t writtenFrames = 0;
    int timeoutStreak = 0;

    while (writtenFrames < totalFrames) {
        const uint8_t* writePtr = data + (writtenFrames * 4);
        int32_t framesLeft = totalFrames - writtenFrames;
        int32_t maxWriteFrames = std::max<int32_t>(96, burstFrames * 2);
        int32_t requestFrames = std::min(framesLeft, maxWriteFrames);
        aaudio_result_t result = AAudioStream_write(stream, writePtr, requestFrames, 15000000);

        if (result > 0) {
            writtenFrames += result;
            timeoutStreak = 0;
            continue;
        }

        if (result == 0 || result == AAUDIO_ERROR_TIMEOUT) {
            timeoutStreak++;
            if (timeoutStreak >= 3) {
                static int timeoutLogCount = 0;
                if ((timeoutLogCount++ % 50) == 0) {
                    LOGE("[Native] AAudio short write timeout (%d/%d frames)",
                         writtenFrames, totalFrames);
                }
                break;
            }
            continue;
        }

        static int errorLogCount = 0;
        if ((errorLogCount++ % 20) == 0) {
            LOGE("[Native] AAudio write error: %d", result);
        }
        break;
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
    maybeSetInputPreset(builder, inputPreset);
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
