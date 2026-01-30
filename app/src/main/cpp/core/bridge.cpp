#include "bridge.h"

#include <tinyalsa/pcm.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <thread>
#include <vector>

#include "../audio/aaudio_engine.h"
#include "../audio/audio_common.h"
#include "../audio/java_audio_track_engine.h"
#include "../audio/opensl_engine.h"
#include "../audio/ring_buffer.h"
#include "../logging/logging.h"

// Define Globals
std::atomic<bool> isRunning{false};
std::atomic<bool> isFinished{true};
std::thread bridgeThread;

// --- Capture Thread ---
// Report actual period size to bridge
void captureLoop(unsigned int card, unsigned int device, RingBuffer *rb,
                 int *out_period_size, int requested_period_size,
                 int requested_rate) {
  setHighPriority();
  struct pcm_config config;
  memset(&config, 0, sizeof(config));
  config.channels = 2;
  config.period_count = 4;
  config.format = PCM_FORMAT_S16_LE;

  struct pcm *pcm = nullptr;

  unsigned int rate = (unsigned int)requested_rate;
  if (rate == 0)
    rate = 48000; // Fallback

  // Configs: Try provided period size, or defaults 1024 (20ms) then 480 (10ms).
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
        LOGD("[Native] PCM Device ready. Waiting for Host stream... (Rate: %u, "
             "Period: %zu)",
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

// --- Playback Loop (Mic -> Gadget) ---
// Reads from Android Mic (InputEngine), writes to USB Gadget (PCM_OUT)
void playbackLoop(unsigned int card, unsigned int device, int sampleRate,
                  int engineType) {
  setHighPriority();
  LOGD("[Native] Starting playback loop (Mic -> Gadget)...");

  struct pcm_config config;
  memset(&config, 0, sizeof(config));
  config.channels = 2;
  config.rate = sampleRate > 0 ? sampleRate : 48000;
  config.period_size = 1024;
  config.period_count = 4;
  config.format = PCM_FORMAT_S16_LE;

  std::unique_ptr<AudioInputEngine> inputEngine;
  // Currently only supporting AAudio for Input for cleanliness, or fallback?
  // Use AAudio for input.
  inputEngine = std::make_unique<AAudioInputEngine>();

  if (!inputEngine->open(config.rate, 2)) {
    LOGE("[Native] Failed to open Mic Input Engine");
    return;
  }
  inputEngine->start();

  // Open USB Gadget PCM OUT
  struct pcm *pcm = pcm_open(card, device, PCM_OUT, &config);
  if (!pcm || !pcm_is_ready(pcm)) {
    LOGE("[Native] Failed to open Gadget PCM OUT: %s",
         pcm ? pcm_get_error(pcm) : "null");
    if (pcm)
      pcm_close(pcm);
    inputEngine->stop();
    inputEngine->close();
    return;
  }

  size_t buffer_bytes = pcm_frames_to_bytes(pcm, config.period_size);
  std::vector<uint8_t> buffer(buffer_bytes);

  LOGD("[Native] Mic -> Gadget streaming active.");

  while (isRunning) {
    size_t readBytes = inputEngine->read(buffer.data(), buffer_bytes);
    if (readBytes > 0) {
      int err = pcm_write(pcm, buffer.data(), readBytes);
      if (err) {
        LOGE("[Native] PCM Write Error: %s", pcm_get_error(pcm));
        // Attempt recovery? or just continue?
        // pcm_prepare(pcm); // might help?
      }
    } else {
      std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
  }

  pcm_close(pcm);
  inputEngine->stop();
  inputEngine->close();
  LOGD("[Native] Playback loop finished.");
}

// --- Bridge Logic ---
void bridgeTask(int card, int device, int bufferSizeFrames,
                int periodSizeFrames, int engineType, int sampleRate,
                int activeDirections) {
  setHighPriority();

  bool enableSpeaker = (activeDirections & 1) != 0;
  bool enableMic = (activeDirections & 2) != 0;

  LOGD("[Native] Bridge task starting. Directions: Speaker=%d, Mic=%d",
       enableSpeaker, enableMic);

  std::thread micThread;
  if (enableMic) {
    // Start Mic -> Gadget pipe in separate thread
    // We assume device 0 for both directions as is standard for UAC2 gadget
    micThread = std::thread(playbackLoop, card, device, sampleRate, engineType);
  }

  if (!enableSpeaker) {
    if (enableMic) {
      reportStateToJava(3); // Streaming (Mic only mode)
      micThread.join();
    }
    reportStateToJava(0);
    isFinished = true;
    if (javaVM)
      javaVM->DetachCurrentThread();
    return;
  }

  // Speaker Logic

  // Use provided buffer size (Minimum 480 to avoid issues)
  size_t deep_buffer_frames = (size_t)std::max(480, bufferSizeFrames);
  LOGD("[Native] Starting Speaker Bridge. Buffer: %zu frames, PeriodReq: %d, "
       "Engine: %d, Rate: %d",
       deep_buffer_frames, periodSizeFrames, engineType, sampleRate);

  size_t bytes_per_frame = 4; // 16-bit stereo
  size_t rb_size = deep_buffer_frames * bytes_per_frame;
  RingBuffer rb(rb_size);

  int actual_period_size = 0;
  std::thread c_thread(captureLoop, card, device, &rb, &actual_period_size,
                       periodSizeFrames, sampleRate);

  int32_t rate = (sampleRate > 0) ? sampleRate : 48000;

  // Select Engine
  AudioEngine *engine = nullptr;
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
    if (c_thread.joinable())
      c_thread.join();
    if (micThread.joinable())
      micThread.join();
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
  if (burstFrames <= 0)
    burstFrames = 192; // Fallback
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
      auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                         now - lastDataTime)
                         .count();
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

  if (c_thread.joinable())
    c_thread.join();
  if (micThread.joinable())
    micThread.join();

  LOGD("[Native] Bridge task finished.");
  reportStateToJava(0); // 0 = STOPPED
  isFinished = true;    // Signal we are mostly done (safe to restart)

  // Detach thread if attached (safe to call even if not attached? No, better
  // check) But we reused helper functions that attach/detach locally. Only
  // JavaAudioTrackEngine calls GetEnv/Attach. Standard JNI practice: Detach if
  // you Attached.
  if (javaVM) {
    javaVM->DetachCurrentThread();
  }
}
