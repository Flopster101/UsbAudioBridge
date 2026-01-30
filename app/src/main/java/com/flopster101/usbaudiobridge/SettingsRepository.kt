package com.flopster101.usbaudiobridge

import android.content.Context

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("UsbAudioSettings", Context.MODE_PRIVATE)

    fun saveBufferSize(size: Float) = prefs.edit().putFloat("buffer_size", size).apply()
    fun getBufferSize(): Float = prefs.getFloat("buffer_size", 4800f)

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
    
    fun saveOriginalIdentity(manufacturer: String, product: String) {
        prefs.edit()
            .putString("orig_man", manufacturer)
            .putString("orig_prod", product)
            .apply()
    }
    
    fun getOriginalIdentity(): Pair<String?, String?> {
        val m = prefs.getString("orig_man", null)
        val p = prefs.getString("orig_prod", null)
        return m to p
    }
    
    fun clearOriginalIdentity() {
        prefs.edit()
            .remove("orig_man")
            .remove("orig_prod")
            .apply()
    }

    fun resetDefaults() {
        prefs.edit().clear().apply()
    }
}
