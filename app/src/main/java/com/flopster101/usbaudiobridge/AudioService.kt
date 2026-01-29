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

class AudioService : Service() {

    companion object {
        const val CHANNEL_ID = "UsbAudioMonitorChannel"
        const val TAG = "AudioService"
        const val ACTION_LOG = "com.flopster101.usbaudiobridge.LOG"
        const val EXTRA_MSG = "msg"
        
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
        intent.putExtra(EXTRA_MSG, "[Native] $msg")
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

    fun isGadgetActive(): Boolean {
        return UsbGadgetManager.isGadgetActive()
    }

    fun enableGadget() {
        // Ensure policies are up to date even if gadget is already active
        UsbGadgetManager.applySeLinuxPolicy { msg -> broadcastLog(msg) }

        if (UsbGadgetManager.isGadgetActive()) {
             broadcastLog("Gadget already active.")
             return
        }
        Thread {
            broadcastLog("Setting up USB Gadget Config...")
            if (UsbGadgetManager.enableGadget { msg -> broadcastLog(msg) }) {
                 broadcastLog("Gadget Configured. Please Connect USB Cable now.")
            } else {
                 broadcastLog("Failed to configure Gadget.")
            }
        }.start()
    }

    fun stopAudioOnly() {
        if (!isBridgeRunning) return
        broadcastLog("Stopping Audio Capture...")
        stopAudioBridge()
        isBridgeRunning = false
        updateNotification("Monitoring Paused (Gadget Active)")
        broadcastLog("Audio Stopped.")
    }

    fun disableGadget() {
        UsbGadgetManager.disableGadget()
    }

    fun startBridge(bufferSize: Int) {
        if (isBridgeRunning) return
        
        Thread {
            broadcastLog("Scanning for Audio Card...")
            val cardId = UsbGadgetManager.findAndPrepareCard { msg -> broadcastLog(msg) }
            
            if (cardId < 0) {
                broadcastLog("ERROR: UAC2 Card not found. Check cable/host.")
                return@Thread
            }

            broadcastLog("Starting Native Capture on Card $cardId...")
            startAudioBridge(cardId, 0, bufferSize)
            
            isBridgeRunning = true
            updateNotification("Monitoring Active")
        }.start()
    }

    fun stopBridge() {
        if (isBridgeRunning) {
            broadcastLog("Stopping Audio Bridge...")
            stopAudioBridge()
            isBridgeRunning = false
            updateNotification("Monitoring Stopped")
            broadcastLog("Audio Stopped.")
        }
        // Always try to disable the gadget
        UsbGadgetManager.disableGadget()
        stopSelf()
    }
    
    private fun broadcastLog(msg: String) {
        val intent = Intent(ACTION_LOG)
        intent.putExtra(EXTRA_MSG, msg)
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
