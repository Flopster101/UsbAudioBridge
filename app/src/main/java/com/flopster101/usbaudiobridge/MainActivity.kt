package com.flopster101.usbaudiobridge

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import android.widget.TextView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var audioService: AudioService? = null
    private var isBound = false

    private lateinit var switchEnable: SwitchMaterial
    private lateinit var sliderBuffer: Slider
    private lateinit var textBufferValue: TextView

    private lateinit var textLog: TextView
    private lateinit var scrollLog: android.widget.ScrollView

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(AudioService.EXTRA_MSG) ?: return
            log(msg)
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val isRunning = intent?.getBooleanExtra(AudioService.EXTRA_IS_RUNNING, false) ?: false
            if (isRunning) {
                btnStartAudio.text = "Stop Audio Capture"
                // Clear log if starting fresh? Maybe not, user might want history.
            } else {
                btnStartAudio.text = "2. Start Audio Capture"
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as AudioService.LocalBinder
            audioService = binder.getService()
            isBound = true
            isBound = true
            log("[App] Service connected")
            restoreUiState()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            audioService = null
            log("[App] Service disconnected")
        }
    }

    private lateinit var btnStartAudio: android.widget.Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        switchEnable = findViewById(R.id.switchEnable)
        btnStartAudio = findViewById(R.id.btnStartAudio)
        sliderBuffer = findViewById(R.id.sliderBuffer)
        textBufferValue = findViewById(R.id.textBufferValue)

        textLog = findViewById(R.id.textLog)
        scrollLog = findViewById(R.id.scrollLog)

        val intent = Intent(this, AudioService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        
        LocalBroadcastManager.getInstance(this).registerReceiver(
            logReceiver, IntentFilter(AudioService.ACTION_LOG)
        )
        LocalBroadcastManager.getInstance(this).registerReceiver(
            stateReceiver, IntentFilter(AudioService.ACTION_STATE_CHANGED)
        )

        setupListeners()
        log("[App] App launched. Waiting for commands...")
    }

    private fun setupListeners() {
        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Always call enableGadget to ensure SELinux policies are reapplied/updated
                audioService?.enableGadget()
                btnStartAudio.isEnabled = true
            } else {
                audioService?.stopBridge() // This stops audio AND disables gadget logic in AudioService
                btnStartAudio.isEnabled = false
                btnStartAudio.text = "2. Start Audio Capture"
            }
        }
        
        btnStartAudio.setOnClickListener {
             if (audioService?.isBridgeRunning == true) {
                 audioService?.stopAudioOnly()
                 btnStartAudio.text = "2. Start Audio Capture"
             } else {
                 val bufferSize = sliderBuffer.value.toInt()
                 audioService?.startBridge(bufferSize)
                 btnStartAudio.text = "Stop Audio Capture"
             }
        }

        sliderBuffer.addOnChangeListener { _, value, _ ->
            val frames = value.toInt()
            val ms = frames / 48
            textBufferValue.text = "Buffer Size: $frames frames (~${ms}ms)"
        }
    }

    private fun restoreUiState() {
        audioService?.let { service ->
             // 1. Check if Gadget is Active (Native check via Root)
             Thread {
                 val gadgetActive = UsbGadgetManager.isGadgetActive()
                 runOnUiThread {
                     if (gadgetActive) {
                         switchEnable.isChecked = true
                         btnStartAudio.isEnabled = true
                         log("[App] Existing USB gadget configuration found.")
                     }
                     
                     // 2. Check if Service is actively streaming
                     if (service.isBridgeRunning) {
                         btnStartAudio.text = "Stop Audio Capture"
                         textLog.text = "" 
                         log("[App] Restored connection to active audio stream")
                     } else {
                         btnStartAudio.text = "2. Start Audio Capture"
                     }
                 }
             }.start()
        }
    }

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$time] $msg\n"
        runOnUiThread {
            // Check if user is scrolled to the bottom (with small tolerance)
            // TextView height grows, so we check if the scroll view is showing the bottom of the TextView
            val viewDiff = (textLog.bottom - (scrollLog.height + scrollLog.scrollY))
            val wasAtBottom = viewDiff <= 100 // 100px tolerance for "at bottom"

            textLog.append(line)
            
            if (wasAtBottom) {
                // Determine new scroll position
                scrollLog.post { 
                    scrollLog.fullScroll(android.view.View.FOCUS_DOWN) 
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(logReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver)
        if (isBound) unbindService(connection)
    }
}
