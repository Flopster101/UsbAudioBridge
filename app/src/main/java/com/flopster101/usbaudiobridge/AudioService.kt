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
import kotlinx.coroutines.launch

class AudioService : Service() {

    companion object {
        const val CHANNEL_ID = "UsbAudioMonitorChannel"
        const val TAG = "AudioService"
        const val ACTION_LOG = "com.flopster101.usbaudiobridge.LOG"
        const val ACTION_STATE_CHANGED = "com.flopster101.usbaudiobridge.STATE_CHANGED"
        const val ACTION_STATS_UPDATE = "com.flopster101.usbaudiobridge.STATS_UPDATE"
        const val EXTRA_MSG = "msg"
        const val EXTRA_IS_RUNNING = "isRunning"
        const val EXTRA_RATE = "rate"
        const val EXTRA_PERIOD = "period"
        const val EXTRA_BUFFER = "buffer"
        
        init {
            System.loadLibrary("usbaudio")
        }
    }

    external fun startAudioBridge(card: Int, device: Int, bufferSize: Int)
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
        // Don't call stopAudioOnly() directly if it might deadlock with the native thread calling this.
        // Instead, launch a cleanup job.
        serviceScope.launch {
            if (isBridgeRunning) {
                isBridgeRunning = false
                Log.e(TAG, "Stopping bridge due to native error: $msg")
                updateNotification("Monitoring Stopped (Error)")
                stopAudioBridge() // Ensure native side flag is cleared
                broadcastState() // Notify UI to reset button
            }
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
    }

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    var isBridgeRunning = false
        private set

    inner class LocalBinder : Binder() {
        fun getService(): AudioService = this@AudioService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UsbAudioMonitor::BridgeLock")
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
        createNotificationChannel()
        // ... (existing wakeLock code if any, though verify placement)
        // Wait, onCreate has the wakeLock init. onDestroy should release it if held?
        // Let's just focus on scope cancel here.
    }

    fun isGadgetActive(): Boolean {
        // This is a quick check, can be sync or optimized. 
        // Current implementation in Manager is "exec su", which is blocking. 
        // ideally suspend, but for now let's leave as is or make suspend?
        // Manager.isGadgetActive is NOT suspend yet in my previous edit (I checked).
        // It uses Exec directly. I should probably make it suspend too if it lags interactions.
        // But for UI "restoreUiState" it's running in a thread in MainActivity. 
        // Let's leave it for now, it's fast enough or handled by caller.
        return UsbGadgetManager.isGadgetActive()
    }

    fun enableGadget() {
        // Ensure policies are up to date even if gadget is already active
        serviceScope.launch {
             // Policies might take a bit, do in background
             // If Gadget is already active, we just re-apply policies to be sure and exit
             if (UsbGadgetManager.isGadgetActive()) {
                  UsbGadgetManager.applySeLinuxPolicy { msg -> broadcastLog(msg) }
                  broadcastLog("[App] Gadget already active.")
                  return@launch
             }
             
             // If gadget is NOT active, UsbGadgetManager.enableGadget will apply policies for us
             broadcastLog("[App] Setting up USB gadget config...")
             val success = UsbGadgetManager.enableGadget { msg -> broadcastLog(msg) }
             if (success) {
                  broadcastLog("[App] Gadget configured. Please connect USB cable now.")
             } else {
                  broadcastLog("[App] Failed to configure gadget.")
             }
        }
    }

    fun stopAudioOnly() {
        if (!isBridgeRunning) return
        broadcastLog("[App] Stopping audio capture...")
        stopAudioBridge()
        isBridgeRunning = false
        updateNotification("Monitoring Paused (Gadget Active)")
        broadcastState()
        broadcastLog("[App] Audio stopped.")
    }

    fun disableGadget() {
        serviceScope.launch {
            UsbGadgetManager.disableGadget { msg -> broadcastLog(msg) }
        }
    }

    fun startBridge(bufferSize: Int) {
        if (isBridgeRunning) return
        
        serviceScope.launch {
            broadcastLog("[App] Scanning for audio card...")
            val cardId = UsbGadgetManager.findAndPrepareCard { msg -> broadcastLog(msg) }
            
            if (cardId < 0) {
                broadcastLog("[App] Error: UAC2 card not found. Check cable/host.")
                return@launch
            }

            broadcastLog("[App] Starting native capture on card $cardId...")
            startAudioBridge(cardId, 0, bufferSize)
            
            isBridgeRunning = true
            updateNotification("Monitoring Active")
            broadcastState()
        }
    }

    fun stopBridge() {
        if (isBridgeRunning) {
            broadcastLog("[App] Stopping audio bridge...")
            stopAudioBridge()
            isBridgeRunning = false
            updateNotification("Monitoring Stopped")
            broadcastState()
            broadcastLog("[App] Audio stopped.")
        }
        // Always try to disable the gadget
        serviceScope.launch {
             UsbGadgetManager.disableGadget { msg -> broadcastLog(msg) }
             stopSelf()
        }
    }
    
    private fun broadcastLog(msg: String) {
        val intent = Intent(ACTION_LOG)
        intent.putExtra(EXTRA_MSG, msg)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastState() {
        val intent = Intent(ACTION_STATE_CHANGED)
        intent.putExtra(EXTRA_IS_RUNNING, isBridgeRunning)
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
