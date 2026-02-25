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
    if (!env || !prepared || !serviceObj || !data || sizeBytes == 0) return;

    if (!directBuffer || directBufferPtr != data || directBufferCapacity < sizeBytes) {
        if (directBuffer) {
            env->DeleteGlobalRef(directBuffer);
            directBuffer = nullptr;
        }

        jobject localBuffer = env->NewDirectByteBuffer((void*)data, sizeBytes);
        if (!localBuffer) return;
        directBuffer = env->NewGlobalRef(localBuffer);
        env->DeleteLocalRef(localBuffer);
        if (!directBuffer) return;
        directBufferPtr = data;
        directBufferCapacity = sizeBytes;
    }

    env->CallVoidMethod(serviceObj, midWrite, directBuffer, (jint)sizeBytes);
}

void JavaAudioTrackEngine::stop() {
    JNIEnv* env = getEnv();
    if (env && prepared && serviceObj) {
        env->CallVoidMethod(serviceObj, midStop);
    }
}

void JavaAudioTrackEngine::close() {
    JNIEnv* env = getEnv();
    if (env && directBuffer) {
        env->DeleteGlobalRef(directBuffer);
        directBuffer = nullptr;
        directBufferPtr = nullptr;
        directBufferCapacity = 0;
    }
    if (env && prepared && serviceObj) {
        env->CallVoidMethod(serviceObj, midRelease);
    }
}

int JavaAudioTrackEngine::getBurstFrames() { return 480; }  // 10ms typical
