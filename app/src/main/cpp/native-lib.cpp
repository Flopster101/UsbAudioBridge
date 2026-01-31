#include <jni.h>

#include <chrono>
#include <thread>

#include "core/bridge.h"
#include "logging/logging.h"

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
  javaVM = vm;
  return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_flopster101_usbaudiobridge_AudioService_startAudioBridge(
    JNIEnv *env, jobject thiz, jint card, jint device, jint bufferSizeFrames,
    jint periodSizeFrames, jint engineType, jint sampleRate,
    jint activeDirections, jint micSource) {
  // Wait for previous instance to clean up
  int safety = 0;
  // Increase timeout to 3s (300 * 10ms) to allow for 1s sleep in captureLoop +
  // cleanup
  while (!isFinished && safety++ < 300) {
    std::this_thread::sleep_for(std::chrono::milliseconds(10));
  }

  if (isRunning)
    return false; // Return false if already running

  // Capture the Service object globally so threads can call back to it
  if (serviceObj)
    env->DeleteGlobalRef(serviceObj);
  serviceObj = env->NewGlobalRef(thiz);

  isRunning = true;
  isFinished = false;
  bridgeThread =
      std::thread(bridgeTask, card, device, bufferSizeFrames, periodSizeFrames,
                  engineType, sampleRate, activeDirections, micSource);
  bridgeThread.detach();
  return true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_flopster101_usbaudiobridge_AudioService_stopAudioBridge(
    JNIEnv *env, jobject /* this */) {
  if (!isRunning)
    return;
  isRunning = false;
  LOGD("[Native] Stop command received.");
}

extern "C" JNIEXPORT void JNICALL
Java_com_flopster101_usbaudiobridge_AudioService_setNativeSpeakerMute(
    JNIEnv *env, jobject /* this */, jboolean muted) {
    isSpeakerMuted = muted;
}

extern "C" JNIEXPORT void JNICALL
Java_com_flopster101_usbaudiobridge_AudioService_setNativeMicMute(
    JNIEnv *env, jobject /* this */, jboolean muted) {
    isMicMuted = muted;
}
