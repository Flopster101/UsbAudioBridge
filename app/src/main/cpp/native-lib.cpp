#include <aaudio/AAudio.h>
#include <algorithm>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <cerrno>
#include <condition_variable>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <jni.h>
#include <mutex>
#include <poll.h>
#include <string>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <thread>
#include <tinyalsa/pcm.h>
#include <unistd.h>
#include <vector>
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>

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

// Helper to report TID to Java for root escalation
void reportTidToJava(int tid) {
  if (!javaVM || !serviceObj)
    return;

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

  jclass cls = env->GetObjectClass(serviceObj);
  jmethodID mid = env->GetMethodID(cls, "onNativeThreadStart", "(I)V");
  if (mid) {
    env->CallVoidMethod(serviceObj, mid, tid);
  }
  env->DeleteLocalRef(cls);

  if (attached) {
    javaVM->DetachCurrentThread();
  }
}

// Helper to set thread priority and request escalation
void setHighPriority() {
  pid_t tid = syscall(SYS_gettid);

  // 1. Set Nice value
  int priority = -19;
  setpriority(PRIO_PROCESS, tid, priority);
  LOGD("[Native] Thread %d nicely set to %d", tid, priority);

  // 2. Request Root Escalation to SCHED_FIFO
  reportTidToJava((int)tid);
}

// Helper to report Fatal Error to Java
void reportErrorToJava(const char *fmt, ...) {
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

  jclass cls = env->GetObjectClass(serviceObj);
  jmethodID mid =
      env->GetMethodID(cls, "onNativeError", "(Ljava/lang/String;)V");
  if (mid) {
    jstring jMsg = env->NewStringUTF(buffer);
    env->CallVoidMethod(serviceObj, mid, jMsg);
    env->DeleteLocalRef(jMsg);
  }
  env->DeleteLocalRef(cls);

  if (attached) {
    javaVM->DetachCurrentThread();
  }
}

// Helper to report State Code (0=Stopped, 1=Connecting, 2=Waiting, 3=Streaming, 4=Idling, 5=Error)
void reportStateToJava(int stateCode) {
  if (!javaVM || !serviceObj) return;

  JNIEnv *env;
  bool attached = false;
  int getEnvStat = javaVM->GetEnv((void **)&env, JNI_VERSION_1_6);

  if (getEnvStat == JNI_EDETACHED) {
    if (javaVM->AttachCurrentThread(&env, nullptr) != 0) return;
    attached = true;
  } else if (getEnvStat != JNI_OK) {
    return;
  }

  jclass cls = env->GetObjectClass(serviceObj);
  jmethodID mid = env->GetMethodID(cls, "onNativeState", "(I)V");
  if (mid) {
    env->CallVoidMethod(serviceObj, mid, stateCode);
  }
  env->DeleteLocalRef(cls);

  if (attached) {
    javaVM->DetachCurrentThread();
  }
}

// Helper to report Stats to Java
void reportStatsToJava(int rate, int period, int bufferSize) {
  if (!javaVM || !serviceObj) {
    LOGE("[Native] Stats report failed: VM or ServiceObj null");
    return;
  }

  JNIEnv *env;
  bool attached = false;
  int getEnvStat = javaVM->GetEnv((void **)&env, JNI_VERSION_1_6);

  if (getEnvStat == JNI_EDETACHED) {
    if (javaVM->AttachCurrentThread(&env, nullptr) != 0) {
      LOGE("[Native] Stats report failed: Could not attach thread");
      return;
    }
    attached = true;
  } else if (getEnvStat != JNI_OK) {
    LOGE("[Native] Stats report failed: GetEnv error %d", getEnvStat);
    return;
  }

  jclass cls = env->GetObjectClass(serviceObj);
  jmethodID mid = env->GetMethodID(cls, "onNativeStats", "(III)V");
  if (mid) {
    env->CallVoidMethod(serviceObj, mid, rate, period, bufferSize);
    if (env->ExceptionCheck()) {
      LOGE("[Native] Exception handling onNativeStats!");
      env->ExceptionDescribe();
      env->ExceptionClear();
    }
  } else {
    LOGE("[Native] Stats report failed: Method ID not found");
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
std::atomic<bool> isFinished{true}; // Synchronization flag
std::thread bridgeThread;

// --- Lock-Free Ring Buffer (SPSC) ---
// Single Producer (Capture), Single Consumer (Bridge)
class RingBuffer {
public:
  RingBuffer(size_t size_bytes) : size_(size_bytes), head_(0), tail_(0) {
    buffer_.resize(size_);
  }

  size_t write(const uint8_t *data, size_t count) {
    size_t current_tail = tail_.load(std::memory_order_acquire);
    size_t available =
        size_ - (head_.load(std::memory_order_relaxed) - current_tail);

    if (count > available)
      return 0;

    size_t write_idx = head_.load(std::memory_order_relaxed) % size_;
    size_t first_chunk = std::min(count, size_ - write_idx);

    memcpy(&buffer_[write_idx], data, first_chunk);
    if (first_chunk < count) {
      memcpy(&buffer_[0], data + first_chunk, count - first_chunk);
    }

    head_.fetch_add(count, std::memory_order_release);
    return count;
  }

  size_t read(uint8_t *dest, size_t count) {
    size_t current_head = head_.load(std::memory_order_acquire);
    size_t available = current_head - tail_.load(std::memory_order_relaxed);

    if (available < count)
      return 0; // Wait for full burst

    size_t read_idx = tail_.load(std::memory_order_relaxed) % size_;
    size_t first_chunk = std::min(count, size_ - read_idx);

    memcpy(dest, &buffer_[read_idx], first_chunk);
    if (first_chunk < count) {
      memcpy(dest + first_chunk, &buffer_[0], count - first_chunk);
    }

    tail_.fetch_add(count, std::memory_order_release);
    return count;
  }

  size_t available() const {
    return head_.load(std::memory_order_acquire) -
           tail_.load(std::memory_order_relaxed);
  }

private:
  std::vector<uint8_t> buffer_;
  size_t size_;
  std::atomic<size_t> head_;
  std::atomic<size_t> tail_;
};

// --- Capture Thread ---
// Added period_size output ptr to report back to bridge
void captureLoop(unsigned int card, unsigned int device, RingBuffer *rb,
                 int *out_period_size, int requested_period_size, int requested_rate) {
  setHighPriority();
  struct pcm_config config;
  memset(&config, 0, sizeof(config));
  config.channels = 2;
  config.period_count = 4;
  config.format = PCM_FORMAT_S16_LE;

  struct pcm *pcm = nullptr;

  unsigned int rate = (unsigned int)requested_rate;
  if (rate == 0) rate = 48000; // Fallback

  // Configs: Try user requested, or defaults 1024 (20ms) then 480 (10ms).
  std::vector<size_t> periods;
  if (requested_period_size > 0) {
      periods.push_back((size_t)requested_period_size);
  } else {
      periods = {1024, 480, 240};
  }

  bool opened = false;

  // Outer loop for retrying connection (waiting for host)
  reportStateToJava(1); // 1 = CONNECTING (Searching/Retrying PCM)
  for (int retry = 0; retry < 20 && isRunning; retry++) {
    config.rate = rate;
    for (size_t p_size : periods) {
      config.period_size = p_size;
      config.period_count = 4;

      pcm = pcm_open(card, device, PCM_IN, &config);

      if (pcm && pcm_is_ready(pcm)) {
        opened = true;
        if (out_period_size)
          *out_period_size = (int)p_size;
        LOGD("[Native] PCM Device ready. Waiting for Host stream... (Rate: %u, Period: %zu)",
             rate, p_size);
        reportStateToJava(2); // 2 = WAITING (PCM Open, No Data)
        break;
      }

      if (pcm) {
        LOGE("[Native] Config %zu failed: %s", p_size, pcm_get_error(pcm));
        pcm_close(pcm);
        pcm = nullptr;
      }
    }

    if (opened)
      break;

    LOGE("[Native] All configs failed. Retrying in 1s...");
    std::this_thread::sleep_for(std::chrono::milliseconds(1000));
  }

  if (!opened || !isRunning) {
    LOGE("[Native] Error: Failed to open PCM after retries.");
    if (pcm) {
      pcm_close(pcm);
      pcm = nullptr;
    }
    isRunning = false;
    return;
  }

  if (!isRunning)
    return;

  unsigned int chunk_bytes = pcm_frames_to_bytes(pcm, config.period_size);
  std::vector<uint8_t> local_buf(chunk_bytes);
  // LOGD("[Native] Capture loop running.");

  int readErrorCount = 0;
  int overrunCount = 0;
  while (isRunning) {
    // Wait up to 100ms for data. This allows checking isRunning frequently.
    int wait_res = pcm_wait(pcm, 100);
    if (wait_res == 0) {
        // Timeout, check isRunning again
        continue;
    }
    
    int res = pcm_read(pcm, local_buf.data(), chunk_bytes);
    if (res == 0) {
      if (rb->write(local_buf.data(), chunk_bytes) == 0) {
        if (overrunCount++ % 50 == 0) {
          LOGE("[Native] RING BUFFER OVERRUN! (dropped %u bytes)", chunk_bytes);
        }
      }
      // Reset error count on success
      readErrorCount = 0;
    } else {
      // Failed read
      if (errno == EAGAIN) {
        // No data available yet. Wait slightly and check isRunning.
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
        continue;
      }

      readErrorCount++;

      const char *err_msg = pcm_get_error(pcm);

      // Log occasionally to avoid spam
      if (readErrorCount % 20 == 0) {
        LOGE("[Native] PCM READ FAILING! (Consecutive: %d, Error: %s)",
             readErrorCount, err_msg);
      }

      // FATAL ERROR CHECK
      // If we fail > 50 times consecutively (approx 50 * 20ms = 1 sec), assume
      // device is dead. Also check for explicit disconnect errors if possible
      // (TinyALSA mostly generic, but -ENODEV/EIO common) We rely on count
      // mainly.
      if (readErrorCount > 50) {
        LOGE("[Native] Too many errors. Assuming USB Disconnect.");
        reportErrorToJava("Capture Failed");
        isRunning = false;
        break;
      }

      // Attempt recovery logic
      // If broken pipe (XRUN), prepare might fix it. If physical disconnect,
      // prepare will fail or read will fail again.
      pcm_prepare(pcm);
    }
  }
  if (pcm) {
    pcm_close(pcm);
    pcm = nullptr;
  }
  LOGD("[Native] Host closed device (Capture stopped).");
}

// --- Audio Output Engine Interface ---
class AudioEngine {
public:
    virtual ~AudioEngine() = default;
    virtual bool open(int rate, int channelCount) = 0;
    virtual void start() = 0;
    virtual void write(const uint8_t* data, size_t sizeBytes) = 0;
    virtual void stop() = 0;
    virtual void close() = 0;
    virtual int getBurstFrames() = 0;
};

// --- AAudio Engine ---
class AAudioEngine : public AudioEngine {
    AAudioStream* stream = nullptr;
    int32_t burstFrames = 0;
public:
    bool open(int rate, int channelCount) override {
        AAudioStreamBuilder *builder;
        AAudio_createStreamBuilder(&builder);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setSampleRate(builder, rate);
        AAudioStreamBuilder_setChannelCount(builder, channelCount);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);

        if (AAudioStreamBuilder_openStream(builder, &stream) != AAUDIO_OK) {
            LOGE("[Native] AAudio open failed");
            AAudioStreamBuilder_delete(builder);
            return false;
        }
        AAudioStreamBuilder_delete(builder);
        burstFrames = AAudioStream_getFramesPerBurst(stream);
        return true;
    }

    void start() override {
        if (stream) AAudioStream_requestStart(stream);
    }

    void write(const uint8_t* data, size_t sizeBytes) override {
        if (stream) {
            // timeout 100ms
            AAudioStream_write(stream, data, sizeBytes / 2, 100000000); // divide by 2 for int16 frames
        }
    }

    void stop() override {
        if (stream) AAudioStream_requestStop(stream);
    }

    void close() override {
        if (stream) {
            AAudioStream_close(stream);
            stream = nullptr;
        }
    }
    
    int getBurstFrames() override { return burstFrames; }
};

// --- OpenSL ES Engine ---
class OpenSLEngine : public AudioEngine {
    SLObjectItf engineObject = nullptr;
    SLEngineItf engineEngine = nullptr;
    SLObjectItf outputMixObject = nullptr;
    SLObjectItf playerObject = nullptr;
    SLPlayItf playerPlay = nullptr;
    SLAndroidSimpleBufferQueueItf playerBufferQueue = nullptr;
    
    std::mutex queueMutex;
    std::condition_variable queueCv;
    bool bufferReady = true; // Simple flow control

    static void bqPlayerCallback(SLAndroidSimpleBufferQueueItf bq, void *context) {
        OpenSLEngine* engine = static_cast<OpenSLEngine*>(context);
        std::lock_guard<std::mutex> lock(engine->queueMutex);
        engine->bufferReady = true;
        engine->queueCv.notify_one();
    }

public:
    bool open(int rate, int channelCount) override {
        SLresult result;
        // 1. Create Engine
        result = slCreateEngine(&engineObject, 0, NULL, 0, NULL, nullptr);
        if(result != SL_RESULT_SUCCESS) return false;
        result = (*engineObject)->Realize(engineObject, SL_BOOLEAN_FALSE);
        if(result != SL_RESULT_SUCCESS) return false;
        result = (*engineObject)->GetInterface(engineObject, SL_IID_ENGINE, &engineEngine);
        if(result != SL_RESULT_SUCCESS) return false;

        // 2. Create Output Mix
        result = (*engineEngine)->CreateOutputMix(engineEngine, &outputMixObject, 0, 0, 0);
        if(result != SL_RESULT_SUCCESS) return false;
        result = (*outputMixObject)->Realize(outputMixObject, SL_BOOLEAN_FALSE);
        if(result != SL_RESULT_SUCCESS) return false;

        // 3. Configure Audio Source
        SLDataLocator_AndroidSimpleBufferQueue loc_bufq = {SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 2};
        SLDataFormat_PCM format_pcm = {SL_DATAFORMAT_PCM, (SLuint32)channelCount, (SLuint32)(rate * 1000),
                                       SL_PCMSAMPLEFORMAT_FIXED_16, SL_PCMSAMPLEFORMAT_FIXED_16,
                                       SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT, SL_BYTEORDER_LITTLEENDIAN};
        SLDataSource audioSrc = {&loc_bufq, &format_pcm};

        // 4. Configure Audio Sink
        SLDataLocator_OutputMix loc_outmix = {SL_DATALOCATOR_OUTPUTMIX, outputMixObject};
        SLDataSink audioSnk = {&loc_outmix, NULL};

        // 5. Create Audio Player
        const SLInterfaceID ids[1] = {SL_IID_ANDROIDSIMPLEBUFFERQUEUE};
        const SLboolean req[1] = {SL_BOOLEAN_TRUE};
        result = (*engineEngine)->CreateAudioPlayer(engineEngine, &playerObject, &audioSrc, &audioSnk, 1, ids, req);
        if(result != SL_RESULT_SUCCESS) { LOGE("OpenSL CreateAudioPlayer failed"); return false; }

        result = (*playerObject)->Realize(playerObject, SL_BOOLEAN_FALSE);
        if(result != SL_RESULT_SUCCESS) return false;

        result = (*playerObject)->GetInterface(playerObject, SL_IID_PLAY, &playerPlay);
        if(result != SL_RESULT_SUCCESS) return false;

        result = (*playerObject)->GetInterface(playerObject, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &playerBufferQueue);
        if(result != SL_RESULT_SUCCESS) return false;

        result = (*playerBufferQueue)->RegisterCallback(playerBufferQueue, bqPlayerCallback, this);
        if(result != SL_RESULT_SUCCESS) return false;

        return true;
    }

    void start() override {
        if(playerPlay) (*playerPlay)->SetPlayState(playerPlay, SL_PLAYSTATE_PLAYING);
    }

    void write(const uint8_t* data, size_t sizeBytes) override {
        if (!playerBufferQueue) return; 
        
        // Wait for buffer slot
        {
            std::unique_lock<std::mutex> lock(queueMutex);
            queueCv.wait_for(lock, std::chrono::milliseconds(100), [this]{ return bufferReady; });
            bufferReady = false; 
        }
        
        (*playerBufferQueue)->Enqueue(playerBufferQueue, data, sizeBytes);
    }

    void stop() override {
        if(playerPlay) (*playerPlay)->SetPlayState(playerPlay, SL_PLAYSTATE_STOPPED);
    }

    void close() override {
        if (playerObject) { (*playerObject)->Destroy(playerObject); playerObject = nullptr; }
        if (outputMixObject) { (*outputMixObject)->Destroy(outputMixObject); outputMixObject = nullptr; }
        if (engineObject) { (*engineObject)->Destroy(engineObject); engineObject = nullptr; }
    }
    
    int getBurstFrames() override { return 192; } // Default approximate burst
};

// --- Legacy Java AudioTrack Engine ---
class JavaAudioTrackEngine : public AudioEngine {
    jclass serviceClass = nullptr;
    jmethodID midInit = nullptr;
    jmethodID midStart = nullptr;
    jmethodID midWrite = nullptr;
    jmethodID midStop = nullptr;
    jmethodID midRelease = nullptr;
    bool prepared = false;

    // Helper to get ENV for the current thread
    JNIEnv* getEnv() {
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

public:
    bool open(int rate, int channelCount) override {
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

    void start() override {
        JNIEnv* env = getEnv();
        if (env && prepared && serviceObj) {
            env->CallVoidMethod(serviceObj, midStart);
        }
    }

    void write(const uint8_t* data, size_t sizeBytes) override {
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

    void stop() override {
        JNIEnv* env = getEnv();
        if (env && prepared && serviceObj) {
            env->CallVoidMethod(serviceObj, midStop);
        }
    }

    void close() override {
        JNIEnv* env = getEnv();
        if (env && prepared && serviceObj) {
            env->CallVoidMethod(serviceObj, midRelease);
        }
    }

    int getBurstFrames() override { return 480; } // 10ms typical
};


// --- Bridge Logic ---
void bridgeTask(int card, int device, int bufferSizeFrames, int periodSizeFrames, int engineType, int sampleRate) {
  setHighPriority();

  // Use user-provided buffer size (Minimum 480 to avoid issues)
  size_t deep_buffer_frames = (size_t)std::max(480, bufferSizeFrames);
  LOGD("[Native] Starting bridge task. Buffer: %zu frames, PeriodReq: %d, Engine: %d, Rate: %d", deep_buffer_frames, periodSizeFrames, engineType, sampleRate);

  size_t bytes_per_frame = 4; // 16-bit stereo
  size_t rb_size = deep_buffer_frames * bytes_per_frame;
  RingBuffer rb(rb_size);

  int actual_period_size = 0;
  std::thread c_thread(captureLoop, card, device, &rb, &actual_period_size, periodSizeFrames, sampleRate);

  int32_t rate = (sampleRate > 0) ? sampleRate : 48000;
  LOGD("[Native] Bridge using rate: %d Hz", rate);

  // Select Engine
  AudioEngine* engine = nullptr;
  if (engineType == 1) {
      engine = new OpenSLEngine();
      LOGD("[Native] Using OpenSL ES Engine");
  } else if (engineType == 2) {
      engine = new JavaAudioTrackEngine();
      LOGD("[Native] Using Legacy AudioTrack Engine");
  } else {
      engine = new AAudioEngine();
      LOGD("[Native] Using AAudio Engine");
  }

  if (!engine->open(rate, 2)) {
      LOGE("[Native] Error: Failed to open Audio Engine.");
      isRunning = false;
      delete engine;
      c_thread.join();
      return;
  }

  engine->start();

  // Pre-roll: Wait briefly for data to populate (prevents immediate underrun)
  // 50ms @ 48kHz = 2400 frames
  int preroll_ms = 50;
  LOGD("[Native] Pre-rolling %dms...", preroll_ms);
  int wait_count = 0;
  while (isRunning &&
         rb.available() < (rate * preroll_ms / 1000) * bytes_per_frame) {
    std::this_thread::sleep_for(std::chrono::milliseconds(5));
  }
  LOGD("[Native] Host opened device (Streaming started).");
  reportStatsToJava(rate, actual_period_size, (int)deep_buffer_frames);

  int32_t burstFrames = engine->getBurstFrames();
  if (burstFrames <= 0) burstFrames = 192; // Fallback
  size_t burstBytes = burstFrames * bytes_per_frame;
  std::vector<uint8_t> p_buf(burstBytes);

  // Consume Loop
  int stats_counter = 0;
  bool isStreaming = true; // Initially true after pre-roll
  auto lastDataTime = std::chrono::steady_clock::now();

  while (isRunning) {
    auto now = std::chrono::steady_clock::now();
    size_t read_bytes = rb.read(p_buf.data(), burstBytes);

    if (read_bytes > 0) {
      lastDataTime = now;
      if (!isStreaming) {
          isStreaming = true;
          // Resume detected
          reportStateToJava(3); // 3 = STREAMING
          reportStatsToJava(rate, actual_period_size, (int)deep_buffer_frames);
          stats_counter = 0; 
      }
      
      engine->write(p_buf.data(), read_bytes);
    } else {
      // Buffer empty. Check for timeout (Idle detection)
      auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - lastDataTime).count();
      if (isStreaming && elapsed > 1000) {
           isStreaming = false;
           reportStateToJava(4); // 4 = IDLING
           LOGD("[Native] Stream idle for 1s. State -> Waiting.");
      }
      std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }

    // Periodic stats update (only when streaming)
    if (isStreaming && ++stats_counter > 500) {
      reportStatsToJava(rate, actual_period_size, (int)deep_buffer_frames);
      stats_counter = 0;
    }
  }

  engine->stop();
  engine->close();
  delete engine;
  
  c_thread.join();
  LOGD("[Native] Bridge task finished.");
  reportStateToJava(0); // 0 = STOPPED
  isFinished = true; // Signal we are mostly done (safe to restart) 
  
  // Detach thread if attached (safe to call even if not attached? No, better check)
  // But we reused helper functions that attach/detach locally.
  // Only JavaAudioTrackEngine calls GetEnv/Attach.
  // Standard JNI practice: Detach if you Attached.
  if (javaVM) {
      javaVM->DetachCurrentThread();
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_flopster101_usbaudiobridge_AudioService_startAudioBridge(
    JNIEnv *env, jobject thiz, jint card, jint device, jint bufferSizeFrames, jint periodSizeFrames, jint engineType, jint sampleRate) {

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
  bridgeThread = std::thread(bridgeTask, card, device, bufferSizeFrames, periodSizeFrames, engineType, sampleRate);
  bridgeThread.detach();
  return true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_flopster101_usbaudiobridge_AudioService_stopAudioBridge(
    JNIEnv *env, jobject /* this */) {
  if (!isRunning)
    return;
  isRunning = false;
  LOGD("[Native] Stop requested.");
}
