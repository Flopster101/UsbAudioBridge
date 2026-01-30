#include "java_audio_track_engine.h"

#include "../logging/logging.h"

JNIEnv* JavaAudioTrackEngine::getEnv() {
    if (!javaVM) return nullptr;
    JNIEnv* env;
    int status = javaVM->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) != 0) {
            return nullptr;
        }
    }
    return env;
}

bool JavaAudioTrackEngine::open(int rate, int channelCount) {
    JNIEnv* env = getEnv();
    if (!env || !serviceObj) return false;

    serviceClass = env->GetObjectClass(serviceObj);
    midInit = env->GetMethodID(serviceClass, "initAudioTrack", "(II)I");
    midStart = env->GetMethodID(serviceClass, "startAudioTrack", "()V");
    midWrite = env->GetMethodID(serviceClass, "writeAudioTrack", "(Ljava/nio/ByteBuffer;I)V");
    midStop = env->GetMethodID(serviceClass, "stopAudioTrack", "()V");
    midRelease = env->GetMethodID(serviceClass, "releaseAudioTrack", "()V");

    if (!midInit || !midStart || !midWrite || !midStop || !midRelease) {
        LOGE("[Native] Failed to find AudioTrack methods");
        env->DeleteLocalRef(serviceClass);
        return false;
    }

    int success = env->CallIntMethod(serviceObj, midInit, rate, channelCount);
    env->DeleteLocalRef(serviceClass);

    prepared = (success > 0);
    return prepared;
}

void JavaAudioTrackEngine::start() {
    JNIEnv* env = getEnv();
    if (env && prepared && serviceObj) {
        env->CallVoidMethod(serviceObj, midStart);
    }
}

void JavaAudioTrackEngine::write(const uint8_t* data, size_t sizeBytes) {
    JNIEnv* env = getEnv();
    if (env && prepared && serviceObj) {
        // Create DirectByteBuffer to avoid copy
        // Note: 'data' pointer must remain valid during the call.
        // Since this is synchronous, it's fine.
        jobject byteBuffer = env->NewDirectByteBuffer((void*)data, sizeBytes);
        if (byteBuffer) {
            env->CallVoidMethod(serviceObj, midWrite, byteBuffer, (jint)sizeBytes);
            env->DeleteLocalRef(byteBuffer);
        }
    }
}

void JavaAudioTrackEngine::stop() {
    JNIEnv* env = getEnv();
    if (env && prepared && serviceObj) {
        env->CallVoidMethod(serviceObj, midStop);
    }
}

void JavaAudioTrackEngine::close() {
    JNIEnv* env = getEnv();
    if (env && prepared && serviceObj) {
        env->CallVoidMethod(serviceObj, midRelease);
    }
}

int JavaAudioTrackEngine::getBurstFrames() { return 480; }  // 10ms typical
