package com.flopster101.usbaudiobridge

import android.content.Context

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("UsbAudioSettings", Context.MODE_PRIVATE)

    fun saveBufferSize(size: Float) = prefs.edit().putFloat("buffer_size", size).apply()
    fun getBufferSize(): Float = prefs.getFloat("buffer_size", 4800f)

    fun saveBufferMode(mode: Int) = prefs.edit().putInt("buffer_mode", mode).apply()
    fun getBufferMode(): Int = prefs.getInt("buffer_mode", 0) // 0 = Simple, 1 = Advanced

    fun saveLatencyPreset(preset: Int) = prefs.edit().putInt("latency_preset", preset).apply()
    fun getLatencyPreset(): Int = prefs.getInt("latency_preset", 2) // 2 = Normal

    fun savePeriodSize(size: Int) = prefs.edit().putInt("period_size", size).apply()
    fun getPeriodSize(): Int = prefs.getInt("period_size", 0)

    fun saveEngineType(type: Int) = prefs.edit().putInt("engine_type", type).apply()
    fun getEngineType(): Int = prefs.getInt("engine_type", 0)

    fun saveSampleRate(rate: Int) = prefs.edit().putInt("sample_rate", rate).apply()
    fun getSampleRate(): Int = prefs.getInt("sample_rate", 48000)
    
    fun saveKeepAdb(enabled: Boolean) = prefs.edit().putBoolean("keep_adb", enabled).apply()
    fun getKeepAdb(): Boolean = prefs.getBoolean("keep_adb", false)

    // If true: auto-restart stream on output change. If false: stop capture when output disconnects.
    fun saveAutoRestartOnOutputChange(enabled: Boolean) = prefs.edit().putBoolean("auto_restart_output", enabled).apply()
    fun getAutoRestartOnOutputChange(): Boolean = prefs.getBoolean("auto_restart_output", false)

    // If true: mute speaker bridge on media button press (Headset Play/Pause)
    fun saveMuteOnMediaButton(enabled: Boolean) = prefs.edit().putBoolean("mute_on_media_button", enabled).apply()
    fun getMuteOnMediaButton(): Boolean = prefs.getBoolean("mute_on_media_button", true)

    // 1 = Speaker (Host->Phone), 2 = Mic (Phone->Host), 3 = Both
    fun saveActiveDirections(mask: Int) = prefs.edit().putInt("active_directions", mask).apply()
    fun getActiveDirections(): Int = prefs.getInt("active_directions", 1)

    // Mic Source (Preset)
    // 0=Unspecified/Auto, 1=Generic, 5=Camcorder, 6=VoiceRec, 7=VoiceComm, 9=Unprocessed, 10=Performance
    fun saveMicSource(source: Int) = prefs.edit().putInt("mic_source", source).apply()
    fun getMicSource(): Int = prefs.getInt("mic_source", 6) // Default to Voice Recognition (6) for best results usually

    // Kernel compatibility notice
    fun shouldShowKernelNotice(): Boolean = !prefs.getBoolean("kernel_notice_dismissed", false)
    fun setKernelNoticeDismissed() = prefs.edit().putBoolean("kernel_notice_dismissed", true).apply()

    // Notification enabled
    fun saveNotificationEnabled(enabled: Boolean) = prefs.edit().putBoolean("notification_enabled", enabled).apply()
    fun getNotificationEnabled(): Boolean = prefs.getBoolean("notification_enabled", true)

    // Keep screen on
    fun saveKeepScreenOn(enabled: Boolean) = prefs.edit().putBoolean("keep_screen_on", enabled).apply()
    fun getKeepScreenOn(): Boolean = prefs.getBoolean("keep_screen_on", false)

    // Screensaver
    fun saveScreensaverEnabled(enabled: Boolean) = prefs.edit().putBoolean("screensaver_enabled", enabled).apply()
    fun getScreensaverEnabled(): Boolean = prefs.getBoolean("screensaver_enabled", false)

    fun saveScreensaverTimeout(seconds: Int) = prefs.edit().putInt("screensaver_timeout", seconds).apply()
    fun getScreensaverTimeout(): Int = prefs.getInt("screensaver_timeout", 15) // Default 15 seconds

    fun saveScreensaverRepositionInterval(seconds: Int) = prefs.edit().putInt("screensaver_reposition_interval", seconds).apply()
    fun getScreensaverRepositionInterval(): Int = prefs.getInt("screensaver_reposition_interval", 5) // Default 5 seconds

    fun saveScreensaverFullscreen(enabled: Boolean) = prefs.edit().putBoolean("screensaver_fullscreen", enabled).apply()
    fun getScreensaverFullscreen(): Boolean = prefs.getBoolean("screensaver_fullscreen", true) // Default true
    
    fun saveOriginalIdentity(manufacturer: String, product: String, serial: String) {
        prefs.edit()
            .putString("orig_man", manufacturer)
            .putString("orig_prod", product)
            .putString("orig_serial", serial)
            .apply()
    }
    
    fun getOriginalIdentity(): Triple<String?, String?, String?> {
        val m = prefs.getString("orig_man", null)
        val p = prefs.getString("orig_prod", null)
        val s = prefs.getString("orig_serial", null)
        return Triple(m, p, s)
    }
    
    fun clearOriginalIdentity() {
        prefs.edit()
            .remove("orig_man")
            .remove("orig_prod")
            .remove("orig_serial")
            .apply()
    }

    // Persist stopped HAL service name to restore it later (even after app kill)
    fun saveStoppedHalService(serviceName: String) = prefs.edit().putString("stopped_hal_service", serviceName).apply()
    fun getStoppedHalService(): String? = prefs.getString("stopped_hal_service", null)
    fun clearStoppedHalService() = prefs.edit().remove("stopped_hal_service").apply()

    fun resetDefaults() {
        prefs.edit().clear().apply()
    }
}
