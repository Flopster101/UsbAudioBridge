package com.flopster101.usbaudiobridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AudioService : Service() {

    companion object {
        const val CHANNEL_ID = "UsbAudioMonitorChannel"
        const val TAG = "AudioService"
        const val ACTION_LOG = "com.flopster101.usbaudiobridge.LOG"
        const val ACTION_STATE_CHANGED = "com.flopster101.usbaudiobridge.STATE_CHANGED"
        const val ACTION_STATS_UPDATE = "com.flopster101.usbaudiobridge.STATS_UPDATE"
        const val ACTION_GADGET_RESULT = "com.flopster101.usbaudiobridge.GADGET_RESULT"
        const val ACTION_GADGET_STATUS = "com.flopster101.usbaudiobridge.GADGET_STATUS"
        const val EXTRA_MSG = "msg"
        const val EXTRA_UDC_CONTROLLER = "udcController"
        const val EXTRA_ACTIVE_FUNCTIONS = "activeFunctions"
        const val EXTRA_IS_RUNNING = "isRunning"
        const val EXTRA_STATE_LABEL = "stateLabel"
        const val EXTRA_STATE_COLOR = "stateColor"
        const val EXTRA_RATE = "rate"
        const val EXTRA_PERIOD = "period"
        const val EXTRA_BUFFER = "buffer"

        // State Codes matching Native
        const val STATE_STOPPED = 0
        const val STATE_CONNECTING = 1
        const val STATE_WAITING = 2
        const val STATE_STREAMING = 3
        const val STATE_IDLING = 4
        const val STATE_ERROR = 5
        
        const val ENGINE_AAUDIO = 0
        const val ENGINE_OPENSL = 1
        const val ENGINE_AUDIOTRACK = 2
        
        init {
            System.loadLibrary("usbaudio")
        }
    }

    private var audioTrack: android.media.AudioTrack? = null

    // Called from C++ JNI
    fun initAudioTrack(rate: Int, channels: Int): Int {
        try {
            val channelConfig = if (channels == 1) android.media.AudioFormat.CHANNEL_OUT_MONO else android.media.AudioFormat.CHANNEL_OUT_STEREO
            val format = android.media.AudioFormat.ENCODING_PCM_16BIT
            val minBuf = android.media.AudioTrack.getMinBufferSize(rate, channelConfig, format)
            val bufferSize = kotlin.math.max(minBuf, rate / 10 * 4) // ~400ms buffer for safety

            audioTrack = android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(android.media.AudioFormat.Builder()
                    .setEncoding(format)
                    .setSampleRate(rate)
                    .setChannelMask(channelConfig)
                    .build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                .build()
            
            return 1 // Success
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack init failed", e)
            return 0 // Fail
        }
    }

    // Called from C++ JNI
    fun startAudioTrack() {
        audioTrack?.play()
    }

    // Called from C++ JNI
    fun writeAudioTrack(buffer: java.nio.ByteBuffer, size: Int) {
        if (audioTrack == null) return
        audioTrack?.write(buffer, size, android.media.AudioTrack.WRITE_BLOCKING)
    }

    // Called from C++ JNI
    fun stopAudioTrack() {
        try {
            if (audioTrack?.playState == android.media.AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack?.stop()
            }
            audioTrack?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioTrack", e)
        }
    }

    // Called from C++ JNI
    fun releaseAudioTrack() {
        try {
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
             Log.e(TAG, "Error releasing AudioTrack", e)
        }
    }

    external fun startAudioBridge(card: Int, device: Int, bufferSize: Int, periodSize: Int, engineType: Int, sampleRate: Int)
    external fun stopAudioBridge()

    // Called from C++ JNI
    fun onNativeLog(msg: String) {
        // Broadcast to Activity
        val intent = Intent(ACTION_LOG)
        intent.putExtra(EXTRA_MSG, msg)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    // Called from C++ JNI
    fun onNativeError(msg: String) {
        broadcastLog("[App] Fatal: $msg")
        serviceScope.launch {
            Log.e(TAG, "Stopping bridge due to native error: $msg")
            
            // Clean up native side immediately
            stopAudioBridge() 
            
            // Update State to ERROR so UI shows it persists
            isBridgeRunning = false
            lastNativeState = STATE_ERROR
            lastErrorMsg = msg
            
            updateNotification("Monitoring Error")
            updateUiState()
        }
    }

    // Called from C++ JNI
    fun onNativeThreadStart(tid: Int) {
        serviceScope.launch(Dispatchers.IO) {
            // Apply SCHED_FIFO (Real-Time) priority
            // -f : FIFO
            // -p 50 : Priority 50 (Range 1-99)
            val cmd = "chrt -f -p 50 $tid"
            UsbGadgetManager.runRootCommand(cmd) { /* ignore output */ }
            Log.d(TAG, "Promoted thread $tid to SCHED_FIFO")
            broadcastLog("[App] Thread $tid promoted to Real-Time (FIFO)")
        }
    }

    // Called from C++ JNI
    fun onNativeStats(rate: Int, period: Int, buffer: Int) {
        val intent = Intent(ACTION_STATS_UPDATE).apply {
            putExtra(EXTRA_RATE, rate)
            putExtra(EXTRA_PERIOD, period)
            putExtra(EXTRA_BUFFER, buffer)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        // Ensure state reflects streaming (in case we missed a transition or to refresh)
        if (lastNativeState != STATE_STREAMING) {
            onNativeState(STATE_STREAMING)
        }
    }

    // Called from C++ JNI
    fun onNativeState(stateCode: Int) {
        lastNativeState = stateCode
        updateUiState()
    }

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    var isBridgeRunning = false
        private set
    
    private lateinit var settingsRepo: SettingsRepository
    private var lastNativeState = STATE_STOPPED
    private var lastErrorMsg = ""

    private val usbReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateUiState()
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): AudioService = this@AudioService
    }

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(this)
        createNotificationChannel()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UsbAudioMonitor::BridgeLock")
        
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(usbReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Service Ready")
        startForeground(1, notification)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        unregisterReceiver(usbReceiver)
        createNotificationChannel()
    }

    private fun checkUsbConnected(): Boolean {
        val intent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = intent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        return plugged == android.os.BatteryManager.BATTERY_PLUGGED_USB || 
               plugged == android.os.BatteryManager.BATTERY_PLUGGED_AC
    }

    private fun updateUiState() {
        // Special Case: Error state should persist even if bridge is "stopped"
        if (lastNativeState == STATE_ERROR) {
            broadcastState("Error ($lastErrorMsg)", 0xFFF44336) // Red
            return
        }

        if (!isBridgeRunning) {
            broadcastState("Stopped", 0xFF888888)
            return
        }

        val isUsb = checkUsbConnected()
        
        // Logic table
        val (label, color) = when (lastNativeState) {
            STATE_CONNECTING -> {
                if (!isUsb) "Active (Not Connected)" to 0xFFFFA000 // Orange
                else "Active (Searching...)" to 0xFFFFC107 // Amber
            }
            STATE_WAITING -> {
                if (!isUsb) "Active (Not Connected)" to 0xFFFFA000 // Orange
                else "Active (Waiting for Host...)" to 0xFFFFC107 // Amber
            }
            STATE_STREAMING -> "Streaming" to 0xFF4CAF50 // Green
            STATE_IDLING -> "Active (Idling)" to 0xFF03A9F4 // Light Blue
            else -> "Active" to 0xFF888888
        }
        broadcastState(label, color)
    }

    fun isGadgetActive(): Boolean {
        return UsbGadgetManager.isGadgetActive()
    }

    fun enableGadget(sampleRate: Int, keepAdb: Boolean) {
        serviceScope.launch {
             if (UsbGadgetManager.isGadgetActive()) {
                  UsbGadgetManager.applySeLinuxPolicy { msg -> broadcastLog(msg) }
                  broadcastLog("[App] Gadget already active.")
                  broadcastGadgetResult(true)
                  return@launch
             }
             
             broadcastLog("[App] Setting up USB gadget config ($sampleRate Hz)...")
             val success = UsbGadgetManager.enableGadget({ msg -> broadcastLog(msg) }, sampleRate, settingsRepo, keepAdb)
             if (success) {
                  broadcastLog("[App] Gadget configured. Please connect USB cable now.")
             } else {
                  broadcastLog("[App] Failed to configure gadget.")
             }
             broadcastGadgetResult(success)
        }
    }
    
    private fun broadcastGadgetResult(success: Boolean) {
        val intent = Intent(ACTION_GADGET_RESULT)
        intent.putExtra("success", success)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        // Also broadcast updated gadget status
        broadcastGadgetStatus()
    }
    
    fun broadcastGadgetStatus() {
        serviceScope.launch(Dispatchers.IO) {
            // Poll for UDC to be populated (HAL may take time to rebind after disable)
            var status = UsbGadgetManager.getGadgetStatus()
            var attempts = 0
            val maxAttempts = 10
            
            // If UDC is empty, poll until it's populated or timeout
            while (!status.isBound && attempts < maxAttempts) {
                delay(200)
                status = UsbGadgetManager.getGadgetStatus()
                attempts++
            }
            
            val intent = Intent(ACTION_GADGET_STATUS).apply {
                putExtra(EXTRA_UDC_CONTROLLER, status.udcController)
                putExtra(EXTRA_ACTIVE_FUNCTIONS, status.activeFunctions.joinToString(", ").ifEmpty { "--" })
            }
            LocalBroadcastManager.getInstance(this@AudioService).sendBroadcast(intent)
        }
    }

    fun stopAudioOnly() {
        // Allow stopping even if bridge not running, to clear Error state
        if (!isBridgeRunning && lastNativeState != STATE_ERROR) return
        
        broadcastLog("[App] Stopping audio capture...")
        stopAudioBridge()
        isBridgeRunning = false
        lastNativeState = STATE_STOPPED
        lastErrorMsg = ""
        updateNotification("Monitoring Paused (Gadget Active)")
        updateUiState()
        broadcastLog("[App] Audio stopped.")
    }

    fun disableGadget() {
        serviceScope.launch {
            UsbGadgetManager.disableGadget({ msg -> broadcastLog(msg) }, settingsRepo)
        }
    }

    fun startBridge(bufferSize: Int, periodSize: Int = 0, engineType: Int = 0, sampleRate: Int = 48000) {
        if (isBridgeRunning) return
        
        serviceScope.launch {
            broadcastLog("[App] Scanning for audio card...")
            val cardId = UsbGadgetManager.findAndPrepareCard { msg -> broadcastLog(msg) }
            
            if (cardId < 0) {
                broadcastLog("[App] Error: UAC2 card not found. Check cable/host.")
                return@launch
            }

            broadcastLog("[App] Starting native capture on card $cardId ($sampleRate Hz)...")
            startAudioBridge(cardId, 0, bufferSize, periodSize, engineType, sampleRate)
            
            isBridgeRunning = true
            lastNativeState = STATE_CONNECTING
            lastErrorMsg = ""
            updateNotification("Monitoring Active")
            updateUiState()
        }
    }

    fun stopBridge() {
        val wasRunning = isBridgeRunning
        
        // Stop native bridge if running
        if (wasRunning) {
            broadcastLog("[App] Stopping audio bridge...")
            stopAudioBridge()
            isBridgeRunning = false
            lastNativeState = STATE_STOPPED
            lastErrorMsg = ""
            updateNotification("Monitoring Stopped")
            updateUiState()
            broadcastLog("[App] Audio stopped.")
        }
        
        serviceScope.launch {
             UsbGadgetManager.disableGadget({ msg -> broadcastLog(msg) }, settingsRepo)
             broadcastGadgetResult(false)  // Notify UI that gadget is now disabled
             stopSelf()
        }
    }
    
    private fun broadcastLog(msg: String) {
        val intent = Intent(ACTION_LOG)
        intent.putExtra(EXTRA_MSG, msg)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastState(label: String, color: Long) {
        val intent = Intent(ACTION_STATE_CHANGED)
        intent.putExtra(EXTRA_IS_RUNNING, isBridgeRunning)
        intent.putExtra(EXTRA_STATE_LABEL, label)
        intent.putExtra(EXTRA_STATE_COLOR, color)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "USB Audio Monitor", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("USB Audio Monitor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, createNotification(text))
    }
}
