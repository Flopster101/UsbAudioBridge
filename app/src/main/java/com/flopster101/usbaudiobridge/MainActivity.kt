package com.flopster101.usbaudiobridge

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize

class MainActivity : ComponentActivity() {

    private var screensaverFullscreenActive = false // Tracks if fullscreen was enabled when screensaver activated
    private lateinit var windowInsetsController: WindowInsetsController
    private var audioService: AudioService? = null
    private lateinit var settingsRepo: SettingsRepository
    
    // Mutable State Holder
    private var uiState by mutableStateOf(MainUiState())
    
    // Root status
    private var isRootGranted by mutableStateOf<Boolean?>(null)

    // Playback device broadcast receiver
    private val playbackDeviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updatePlaybackDeviceType()
        }
    }

    private fun updatePlaybackDeviceType() {
        val type = PlaybackDeviceHelper.getCurrentPlaybackDevice(this)
        uiState = uiState.copy(playbackDeviceType = type)
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startBridgeWithState()
        } else {
            appendLog("[App] Microphone permission denied. Input will fail.")
            startBridgeWithState()
        }
    }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(AudioService.EXTRA_MSG) ?: return
            appendLog(msg)
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val isRunning = intent?.getBooleanExtra(AudioService.EXTRA_IS_RUNNING, false) ?: false
            val label = intent?.getStringExtra(AudioService.EXTRA_STATE_LABEL) 
            val color = intent?.getLongExtra(AudioService.EXTRA_STATE_COLOR, 0)
            val directions = intent?.getIntExtra(AudioService.EXTRA_ACTIVE_DIRECTIONS, 0) ?: 0
            val isMuted = intent?.getBooleanExtra(AudioService.EXTRA_IS_MUTED, false) ?: false
            
            uiState = uiState.copy(
                isServiceRunning = isRunning,
                runningDirections = directions,
                speakerMuted = isMuted,
                serviceState = label ?: if (isRunning) "Active" else "Stopped",
                serviceStateColor = if (color != null && color != 0L) color else if (isRunning) 0xFFFFC107 else 0xFF888888
            )

            // Clear capture pending when state changes
            uiState = uiState.copy(isCapturePending = false)

            if (!isRunning) {
                uiState = uiState.copy(
                    sampleRate = "--",
                    periodSize = "--",
                    currentBuffer = "--"
                )
            }
        }
    }

    private val statsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val rate = intent.getIntExtra(AudioService.EXTRA_RATE, 0)
            val period = intent.getIntExtra(AudioService.EXTRA_PERIOD, 0)
            val buffer = intent.getIntExtra(AudioService.EXTRA_BUFFER, 0)

            // State label is handled by stateReceiver now via Service broadcast
            uiState = uiState.copy(
                sampleRate = "$rate Hz",
                periodSize = "$period frames",
                currentBuffer = "$buffer frames"
            )
        }
    }
    
    private val gadgetResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val success = intent.getBooleanExtra("success", false)
            uiState = uiState.copy(isGadgetEnabled = success, isGadgetPending = false)
            audioService?.setGadgetEnabled(success)
        }
    }
    
    private val gadgetStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val udcController = intent.getStringExtra(AudioService.EXTRA_UDC_CONTROLLER) ?: "--"
            val activeFunctions = intent.getStringExtra(AudioService.EXTRA_ACTIVE_FUNCTIONS) ?: "--"
            uiState = uiState.copy(
                udcController = udcController,
                activeFunctions = activeFunctions
            )
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as AudioService.LocalBinder
            audioService = binder.getService()
            uiState = uiState.copy(isAppBound = true)
            audioService?.setGadgetEnabled(uiState.isGadgetEnabled)
            appendLog("[App] Service connected")
            restoreUiState()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            uiState = uiState.copy(isAppBound = false)
            audioService = null
            appendLog("[App] Service disconnected")
        }
    }

    private fun startServiceAndBind() {
        // Start Service
        val intent = Intent(this, AudioService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun checkRoot() {
        // Only show loading state if we are doing a fresh check or retry
        if (isRootGranted == false) {
             isRootGranted = null
        } else if (isRootGranted == null) {
             // Already loading or initial state, keep null
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val granted = UsbGadgetManager.isRootGranted()
            withContext(Dispatchers.Main) {
                isRootGranted = granted
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isRootGranted == false) {
            checkRoot()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize WindowInsetsController for modern system UI control (screensaver fullscreen)
        windowInsetsController = window.insetsController!!

        settingsRepo = SettingsRepository(this)
        // Load settings
        uiState = uiState.copy(
            bufferSize = settingsRepo.getBufferSize(),
            periodSizeOption = settingsRepo.getPeriodSize(),
            engineTypeOption = settingsRepo.getEngineType(),
            sampleRateOption = settingsRepo.getSampleRate(),
            keepAdbOption = settingsRepo.getKeepAdb(),
            autoRestartOnOutputChange = settingsRepo.getAutoRestartOnOutputChange(),
            activeDirectionsOption = settingsRepo.getActiveDirections(),
            micSourceOption = settingsRepo.getMicSource(),
            notificationEnabled = settingsRepo.getNotificationEnabled(),
            keepScreenOnOption = settingsRepo.getKeepScreenOn(),
            screensaverEnabled = settingsRepo.getScreensaverEnabled(),
            screensaverTimeout = settingsRepo.getScreensaverTimeout(),
            screensaverRepositionInterval = settingsRepo.getScreensaverRepositionInterval(),
            screensaverFullscreen = settingsRepo.getScreensaverFullscreen(),
            muteOnMediaButton = settingsRepo.getMuteOnMediaButton()
        )

        // Apply initial keep screen on
        if (uiState.keepScreenOnOption) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Register playback device receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        registerReceiver(playbackDeviceReceiver, filter)

        // Initial update
        updatePlaybackDeviceType()

        // Fallback timer (5s interval)
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5000)
                updatePlaybackDeviceType()
            }
        }

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }

        // Register Receivers
        ContextCompat.registerReceiver(this, logReceiver, IntentFilter(AudioService.ACTION_LOG), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, stateReceiver, IntentFilter(AudioService.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, statsReceiver, IntentFilter(AudioService.ACTION_STATS_UPDATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, gadgetResultReceiver, IntentFilter(AudioService.ACTION_GADGET_RESULT), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, gadgetStatusReceiver, IntentFilter(AudioService.ACTION_GADGET_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)

        setContent {
            LaunchedEffect(Unit) {
                checkRoot()
            }
            
            LaunchedEffect(isRootGranted) {
                if (isRootGranted == true) {
                    startServiceAndBind()
                }
            }

            // Basic Material Theme wrapper
            MaterialTheme(
                colorScheme = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) 
                    dynamicDarkColorScheme(LocalContext.current) else darkColorScheme()
            ) {
                when (isRootGranted) {
                    null -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    false -> {
                        NoRootScreen(onRetry = { checkRoot() })
                    }
                    true -> {
                        // First-run kernel notice dialog
                        if (uiState.showKernelNotice) {
                            KernelNoticeDialog(
                                onDismiss = { dontShowAgain ->
                                    if (dontShowAgain) {
                                        settingsRepo.setKernelNoticeDismissed()
                                    }
                                    uiState = uiState.copy(showKernelNotice = false)
                                }
                            )
                        }
                        
                        AppNavigation(
                            state = uiState,
                            onToggleGadget = { enable ->
                                 if (enable) {
                                     // Set pending state, wait for result broadcast to confirm
                                     uiState = uiState.copy(isGadgetPending = true)
                                     audioService?.enableGadget(uiState.sampleRateOption, uiState.keepAdbOption)
                                 } else {
                                     uiState = uiState.copy(isGadgetEnabled = false, isGadgetPending = true)
                                     audioService?.setGadgetEnabled(false)
                                     audioService?.stopBridge()
                                     // Don't wait for broadcast result for disable
                                 }
                            },
                            onToggleCapture = {
                                if (uiState.isServiceRunning) {
                                     uiState = uiState.copy(isCapturePending = true)
                                     audioService?.stopAudioOnly()
                                } else {
                                     uiState = uiState.copy(isCapturePending = true)
                                     val perm = android.Manifest.permission.RECORD_AUDIO
                                     if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                                         requestPermissionLauncher.launch(perm)
                                     } else {
                                         startBridgeWithState()
                                     }
                                }
                            },
                            onBufferSizeChange = { 
                                uiState = uiState.copy(bufferSize = it)
                                settingsRepo.saveBufferSize(it)
                            },
                            onPeriodSizeChange = {
                                uiState = uiState.copy(periodSizeOption = it)
                                settingsRepo.savePeriodSize(it)
                            },
                            onEngineTypeChange = {
                                uiState = uiState.copy(engineTypeOption = it)
                                settingsRepo.saveEngineType(it)
                            },
                            onSampleRateChange = {
                                uiState = uiState.copy(sampleRateOption = it)
                                settingsRepo.saveSampleRate(it)
                            },
                            onKeepAdbChange = {
                                uiState = uiState.copy(keepAdbOption = it)
                                settingsRepo.saveKeepAdb(it)
                            },
                            onAutoRestartChange = {
                                uiState = uiState.copy(autoRestartOnOutputChange = it)
                                settingsRepo.saveAutoRestartOnOutputChange(it)
                            },
                            onActiveDirectionsChange = {
                                uiState = uiState.copy(activeDirectionsOption = it)
                                settingsRepo.saveActiveDirections(it)
                            },
                            onMicSourceChange = {
                                 uiState = uiState.copy(micSourceOption = it)
                                 settingsRepo.saveMicSource(it)
                            },
                            onNotificationEnabledChange = {
                                uiState = uiState.copy(notificationEnabled = it)
                                settingsRepo.saveNotificationEnabled(it)
                                audioService?.refreshNotification()
                            },
                            onKeepScreenOnChange = {
                                uiState = uiState.copy(keepScreenOnOption = it)
                                settingsRepo.saveKeepScreenOn(it)
                                if (it) {
                                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                } else {
                                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                    // Disable screensaver if keep screen on is disabled
                                    if (uiState.screensaverEnabled) {
                                        uiState = uiState.copy(screensaverEnabled = false)
                                        settingsRepo.saveScreensaverEnabled(false)
                                    }
                                }
                            },
                            onScreensaverEnabledChange = {
                                uiState = uiState.copy(screensaverEnabled = it)
                                settingsRepo.saveScreensaverEnabled(it)
                            },
                            onScreensaverTimeoutChange = {
                                uiState = uiState.copy(screensaverTimeout = it)
                                settingsRepo.saveScreensaverTimeout(it)
                            },
                            onScreensaverRepositionIntervalChange = {
                                uiState = uiState.copy(screensaverRepositionInterval = it)
                                settingsRepo.saveScreensaverRepositionInterval(it)
                            },
                            onScreensaverFullscreenChange = {
                                uiState = uiState.copy(screensaverFullscreen = it)
                                settingsRepo.saveScreensaverFullscreen(it)
                            },
                            onScreensaverActivate = {
                                uiState = uiState.copy(screensaverActive = true)
                                screensaverFullscreenActive = uiState.screensaverFullscreen
                                if (screensaverFullscreenActive) {
                                    // Hide system bars using WindowInsetsController for immersive screensaver experience
                                    windowInsetsController.hide(WindowInsets.Type.systemBars())
                                    windowInsetsController.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                                }
                            },
                            onScreensaverDeactivate = {
                                uiState = uiState.copy(screensaverActive = false)
                                if (screensaverFullscreenActive) {
                                    // Show system bars using WindowInsetsController - using separate flag to avoid state issues
                                    windowInsetsController.show(WindowInsets.Type.systemBars())
                                    screensaverFullscreenActive = false
                                }
                            },
                            onToggleSpeakerMute = {
                                uiState = uiState.copy(speakerMuted = !uiState.speakerMuted)
                                audioService?.setSpeakerMuted(uiState.speakerMuted)
                            },
                            onToggleMicMute = {
                                uiState = uiState.copy(micMuted = !uiState.micMuted)
                                audioService?.setMicMuted(uiState.micMuted)
                            },
                            onMuteOnMediaButtonChange = {
                                uiState = uiState.copy(muteOnMediaButton = it)
                                settingsRepo.saveMuteOnMediaButton(it)
                            },
                            onResetSettings = {
                                settingsRepo.resetDefaults()
                                uiState = uiState.copy(
                                    bufferSize = settingsRepo.getBufferSize(),
                                    periodSizeOption = settingsRepo.getPeriodSize(),
                                    engineTypeOption = settingsRepo.getEngineType(),
                                    sampleRateOption = settingsRepo.getSampleRate(),
                                    keepAdbOption = settingsRepo.getKeepAdb(),

                                    autoRestartOnOutputChange = settingsRepo.getAutoRestartOnOutputChange(),
                                    activeDirectionsOption = settingsRepo.getActiveDirections(),
                                    micSourceOption = settingsRepo.getMicSource(),
                                    notificationEnabled = settingsRepo.getNotificationEnabled(),
                                    keepScreenOnOption = settingsRepo.getKeepScreenOn(),
                                    screensaverEnabled = settingsRepo.getScreensaverEnabled(),
                                    screensaverTimeout = settingsRepo.getScreensaverTimeout(),
                                    screensaverRepositionInterval = settingsRepo.getScreensaverRepositionInterval(),
                                    screensaverFullscreen = settingsRepo.getScreensaverFullscreen(),
                                    muteOnMediaButton = settingsRepo.getMuteOnMediaButton()
                                )
                            },
                            onToggleLogs = { uiState = uiState.copy(isLogsExpanded = !uiState.isLogsExpanded) }
                        )
                    }
                }
            }
        }
    }

    private fun restoreUiState() {
        audioService?.let { service ->
             Thread {
                 val gadgetActive = UsbGadgetManager.isGadgetActive()
                 runOnUiThread {
                     uiState = uiState.copy(isGadgetEnabled = gadgetActive)
                     service.setGadgetEnabled(gadgetActive)
                     
                     if (service.isBridgeRunning) {
                         uiState = uiState.copy(isServiceRunning = true)
                         appendLog("[App] Restored connection to active stream")
                     }
                 }
                 // Fetch and broadcast current state
                 service.updateUiState()
                 service.refreshNotification()
                 // Fetch and broadcast gadget status
                 service.broadcastGadgetStatus()
             }.start()
        }
    }

    private fun appendLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$time] $msg\n"
        
        var currentText = uiState.logText + line
        // Circular Buffer Logic
        if (currentText.length > 100000) {
            val excess = currentText.length - 80000
            val cutIndex = currentText.indexOf('\n', excess)
            if (cutIndex != -1) {
                currentText = currentText.substring(cutIndex + 1)
            }
        }
        uiState = uiState.copy(logText = currentText)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (isRootGranted == true) {
                startServiceAndBind()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(logReceiver)
        unregisterReceiver(stateReceiver)
        unregisterReceiver(statsReceiver)
        unregisterReceiver(gadgetResultReceiver)
        unregisterReceiver(gadgetStatusReceiver)
        if (uiState.isAppBound) unbindService(connection)
    }

    private fun startBridgeWithState() {
        audioService?.startBridge(
             uiState.bufferSize.toInt(), 
             uiState.periodSizeOption, 
             uiState.engineTypeOption, 
             uiState.sampleRateOption,
             uiState.activeDirectionsOption,
             uiState.micSourceOption
        )
    }
}