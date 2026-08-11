#include "oboe_engine.h"

#include <algorithm>

#include "../logging/logging.h"

namespace {
constexpr int64_t kWriteTimeoutNanos = 15000000;
constexpr int32_t kMaxTimeoutStreak = 100;
}  // namespace

// --- Oboe Error Callback ---

bool OboeErrorCallback::onError(oboe::AudioStream*, oboe::Result error) {
    if (error == oboe::Result::ErrorDisconnected) {
        bool newlySet = enginePtr && enginePtr->setDisconnected();
        if (newlySet) {
            reportOutputDisconnectToJava();
        }
    } else {
        LOGE("[Native] Oboe error callback: error %d", static_cast<int>(error));
    }
    return false;
}

void OboeErrorCallback::onErrorBeforeClose(oboe::AudioStream*, oboe::Result error) {
    if (error != oboe::Result::ErrorDisconnected) {
        LOGE("[Native] Oboe error callback: error %d", static_cast<int>(error));
    }
}

// --- Oboe Output Engine ---

bool OboeEngine::setDisconnected() {
    if (disconnected.exchange(true)) {
        return false;
    }
    LOGD("[Native] Oboe stream marked as disconnected");
    return true;
}

bool OboeEngine::open(int rate, int channelCount) {
    errorCallback.setEngine(this);

    oboe::AudioStreamBuilder builder;
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setSharingMode(oboe::SharingMode::Shared);
    builder.setDirection(oboe::Direction::Output);
    builder.setSampleRate(rate);
    builder.setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None);
    builder.setChannelCount(channelCount);
    builder.setFormat(oboe::AudioFormat::I16);
    std::shared_ptr<oboe::AudioStreamErrorCallback> sharedError(
        &errorCallback, [](oboe::AudioStreamErrorCallback*) {});
    builder.setErrorCallback(sharedError);

    oboe::Result result = builder.openStream(stream);
    if (result != oboe::Result::OK) {
        LOGE("[Native] Oboe open failed: %d", static_cast<int>(result));
        return false;
    }
    burstFrames = stream->getFramesPerBurst();
    if (burstFrames <= 0) {
        burstFrames = 192;
    }

    int32_t capacityFrames = stream->getBufferCapacityInFrames();
    if (capacityFrames > 0) {
        int32_t targetFrames = std::max(burstFrames * 4, (capacityFrames * 3) / 4);
        targetFrames = std::min(targetFrames, capacityFrames);
        auto setFrames = stream->setBufferSizeInFrames(targetFrames);
        if (setFrames.error() == oboe::Result::OK) {
            LOGD("[Native] Oboe buffer frames: burst=%d capacity=%d target=%d actual=%d",
                 burstFrames, capacityFrames, targetFrames, setFrames.value());
        }
    }
    return true;
}

void OboeEngine::start() {
    if (stream) stream->requestStart();
}

void OboeEngine::write(const uint8_t* data, size_t sizeBytes) {
    if (!stream || sizeBytes < 4) return;

    int32_t totalFrames = static_cast<int32_t>(sizeBytes / 4);
    int32_t writtenFrames = 0;
    int timeoutStreak = 0;

    while (writtenFrames < totalFrames) {
        if (disconnected.load()) {
            return;
        }
        const uint8_t* writePtr = data + (writtenFrames * 4);
        int32_t framesLeft = totalFrames - writtenFrames;
        int32_t maxWriteFrames = std::max<int32_t>(96, burstFrames * 2);
        int32_t requestFrames = std::min(framesLeft, maxWriteFrames);
        auto result = stream->write(writePtr, requestFrames, kWriteTimeoutNanos);

        if (result.error() == oboe::Result::OK) {
            writtenFrames += result.value();
            timeoutStreak = 0;
            continue;
        }

        oboe::Result error = result.error();
        if (error == oboe::Result::ErrorDisconnected ||
            error == oboe::Result::ErrorClosed ||
            error == oboe::Result::ErrorInvalidHandle) {
            LOGD("[Native] Oboe write failed: stream no longer usable (%d)",
                 static_cast<int>(error));
            if (error == oboe::Result::ErrorDisconnected && setDisconnected()) {
                reportOutputDisconnectToJava();
            }
            break;
        }

        if (error == oboe::Result::ErrorTimeout) {
            if (++timeoutStreak >= kMaxTimeoutStreak) {
                static int timeoutLogCount = 0;
                if ((timeoutLogCount++ % 20) == 0) {
                    LOGE("[Native] Oboe write stalled (%d/%d frames), giving up.",
                         writtenFrames, totalFrames);
                }
                break;
            }
            continue;
        }

        static int errorLogCount = 0;
        if ((errorLogCount++ % 20) == 0) {
            LOGE("[Native] Oboe write error: %d", static_cast<int>(error));
        }
        break;
    }
}

void OboeEngine::stop() {
    if (stream) stream->requestStop();
}

void OboeEngine::close() {
    if (stream) {
        stream->close();
        stream.reset();
    }
}

int OboeEngine::getBurstFrames() { return burstFrames; }