#include <aaudio/AAudio.h>
#include <android/log.h>
#include <atomic>
#include <condition_variable>
#include <jni.h>
#include <mutex>
#include <string>
#include <thread>
#include <tinyalsa/pcm.h>
#include <vector>

#define TAG "UsbAudioNative"
// Redirect logs to both Logcat and Java Callback
#define LOGD(...)                                                              \
  __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__);                    \
  logToJava(__VA_ARGS__)
#define LOGE(...)                                                              \
  __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__);                    \
  logToJava(__VA_ARGS__)

// JNI State
JavaVM *javaVM = nullptr;
jobject serviceObj = nullptr;

// Helper to send logs back to Java
void logToJava(const char *fmt, ...) {
  if (!javaVM || !serviceObj)
    return;

  char buffer[512];
  va_list args;
  va_start(args, fmt);
  vsnprintf(buffer, sizeof(buffer), fmt, args);
  va_end(args);

  JNIEnv *env;
  bool attached = false;
  int getEnvStat = javaVM->GetEnv((void **)&env, JNI_VERSION_1_6);

  if (getEnvStat == JNI_EDETACHED) {
    if (javaVM->AttachCurrentThread(&env, nullptr) != 0)
      return;
    attached = true;
  } else if (getEnvStat != JNI_OK) {
    return;
  }

  // Call onNativeLog(String msg) on the Service object
  jclass cls = env->GetObjectClass(serviceObj);
  jmethodID mid = env->GetMethodID(cls, "onNativeLog", "(Ljava/lang/String;)V");
  if (mid) {
    jstring jLog = env->NewStringUTF(buffer);
    env->CallVoidMethod(serviceObj, mid, jLog);
    env->DeleteLocalRef(jLog);
  }
  env->DeleteLocalRef(cls);

  if (attached) {
    javaVM->DetachCurrentThread();
  }
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
  javaVM = vm;
  return JNI_VERSION_1_6;
}

// Global Execution State
std::atomic<bool> isRunning{false};
std::thread bridgeThread;

// --- Ring Buffer Implementation ---
class RingBuffer {
public:
  RingBuffer(size_t size_bytes) : size_(size_bytes), head_(0), tail_(0) {
    buffer_.resize(size_);
  }

  size_t write(const uint8_t *data, size_t count) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (count > (size_ - (head_ - tail_)))
      return 0;

    size_t write_idx = head_ % size_;
    size_t first_chunk = std::min(count, size_ - write_idx);
    memcpy(&buffer_[write_idx], data, first_chunk);
    if (first_chunk < count) {
      memcpy(&buffer_[0], data + first_chunk, count - first_chunk);
    }
    head_ += count;
    cv_.notify_one();
    return count;
  }

  size_t read(uint8_t *dest, size_t count, bool blocking) {
    std::unique_lock<std::mutex> lock(mutex_);
    if (blocking)
      cv_.wait(lock, [&] { return (head_ - tail_) >= count || !isRunning; });
    if (!isRunning)
      return 0;

    size_t available = head_ - tail_;
    if (available == 0)
      return 0;
    size_t to_read = std::min(available, count);

    size_t read_idx = tail_ % size_;
    size_t first_chunk = std::min(to_read, size_ - read_idx);
    memcpy(dest, &buffer_[read_idx], first_chunk);
    if (first_chunk < to_read) {
      memcpy(dest + first_chunk, &buffer_[0], to_read - first_chunk);
    }
    tail_ += to_read;
    return to_read;
  }

  size_t available() {
    std::lock_guard<std::mutex> lock(mutex_);
    return head_ - tail_;
  }

private:
  std::vector<uint8_t> buffer_;
  size_t size_, head_, tail_;
  std::mutex mutex_;
  std::condition_variable cv_;
};

// --- Capture Thread ---
void captureLoop(unsigned int card, unsigned int device, RingBuffer *rb) {
  struct pcm_config config;
  memset(&config, 0, sizeof(config));
  config.channels = 2;
  config.rate = 48000;
  config.period_size = 1024;
  config.period_count = 4;
  config.format = PCM_FORMAT_S16_LE;

  struct pcm *pcm = nullptr;

  // Configs to try: multiples of 48 (1ms) are preferred for USB Audio @ 48kHz
  const size_t periods[] = {512, 480, 1024, 960, 240, 1920};

  bool opened = false;

  // Outer loop for retrying connection (waiting for host)
  // Inner loop for trying different parameters

  for (int retry = 0; retry < 20 && isRunning; retry++) {
    for (size_t p_size : periods) {
      config.period_size = p_size;
      config.period_count = 4;

      // LOGD("Native: Trying period %u...", p_size);
      pcm = pcm_open(card, device, PCM_IN, &config);

      if (pcm && pcm_is_ready(pcm)) {
        opened = true;
        LOGD("Native: Success! Opened with period=%u", p_size);
        break;
      }

      if (pcm)
        pcm_close(pcm);
    }

    if (opened)
      break;

    LOGE("Native: All configs failed. Retrying in 1s...");
    std::this_thread::sleep_for(std::chrono::milliseconds(1000));
  }

  if (!opened || !isRunning) {
    LOGE("Native Error: Failed to open PCM after retries.");
    if (pcm)
      pcm_close(pcm);
    isRunning = false;
    return;
  }

  if (!isRunning)
    return; // Stopped while waiting

  unsigned int chunk_bytes = pcm_frames_to_bytes(pcm, config.period_size);
  std::vector<uint8_t> local_buf(chunk_bytes);
  LOGD("Native: Capture Started.");

  while (isRunning) {
    if (pcm_read(pcm, local_buf.data(), chunk_bytes) == 0) {
      rb->write(local_buf.data(), chunk_bytes);
    } else {
      // LOGE("PCM Read Error"); // Prevent spamming UI
      std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
  }
  pcm_close(pcm);
  LOGD("Native: Capture Stopped.");
}

// --- Bridge Logic ---
void bridgeTask(int card, int device, int bufferSizeFrames) {
  LOGD("Native: Starting Bridge Task. Buffer: %d frames", bufferSizeFrames);

  size_t bytes_per_frame = 4; // 16-bit stereo
  size_t rb_size = bufferSizeFrames * bytes_per_frame;
  RingBuffer rb(rb_size);

  std::thread c_thread(captureLoop, card, device, &rb);

  AAudioStreamBuilder *builder;
  AAudio_createStreamBuilder(&builder);
  AAudioStreamBuilder_setPerformanceMode(builder,
                                         AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
  AAudioStreamBuilder_setSampleRate(builder, 48000);
  AAudioStreamBuilder_setChannelCount(builder, 2);
  AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);

  AAudioStream *stream;
  if (AAudioStreamBuilder_openStream(builder, &stream) != AAUDIO_OK) {
    LOGE("Native Error: Failed to open AAudio.");
    isRunning = false;
    c_thread.join();
    return;
  }
  AAudioStreamBuilder_delete(builder);

  AAudioStream_requestStart(stream);

  // Pre-buffer 50%
  LOGD("Native: Pre-buffering...");
  while (isRunning && rb.available() < (rb_size / 2)) {
    std::this_thread::sleep_for(std::chrono::milliseconds(5));
  }
  LOGD("Native: Playback Started!");

  int32_t burstFrames = AAudioStream_getFramesPerBurst(stream);
  size_t burstBytes = burstFrames * bytes_per_frame;
  std::vector<uint8_t> p_buf(burstBytes);

  while (isRunning) {
    size_t read_bytes = rb.read(p_buf.data(), burstBytes, true);
    if (read_bytes > 0) {
      AAudioStream_write(stream, p_buf.data(), read_bytes / bytes_per_frame,
                         100000000);
    }
  }

  AAudioStream_requestStop(stream);
  AAudioStream_close(stream);
  c_thread.join();
  LOGD("Native: Bridge Task Finished.");

  // Clean up Global Ref when done?
  // Ideally yes, but since we are detaching, we'll leave it for stopBridge
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_flopster101_usbaudiobridge_AudioService_startAudioBridge(
    JNIEnv *env, jobject thiz, jint card, jint device, jint bufferSizeFrames) {

  if (isRunning)
    return false; // Return false if already running

  // Capture the Service object globally so threads can call back to it
  if (serviceObj)
    env->DeleteGlobalRef(serviceObj);
  serviceObj = env->NewGlobalRef(thiz);

  isRunning = true;
  bridgeThread = std::thread(bridgeTask, card, device, bufferSizeFrames);
  bridgeThread.detach();
  return true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_flopster101_usbaudiobridge_AudioService_stopAudioBridge(
    JNIEnv *env, jobject /* this */) {
  if (!isRunning)
    return;
  isRunning = false;
  LOGD("Native: Stop Requested.");
}
