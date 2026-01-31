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
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check

import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share

import androidx.compose.ui.res.painterResource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.ClipboardManager
import android.content.ClipData
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

// UI State Definition
data class MainUiState(
    val isGadgetEnabled: Boolean = false,
    val isGadgetPending: Boolean = false,  // Gadget operation in progress?
    val isServiceRunning: Boolean = false, // Streaming active?
    val isCapturePending: Boolean = false, // Capture operation in progress?
    val runningDirections: Int = 0,        // Active directions reported by service
    val isAppBound: Boolean = false,       // Service bound?
    
    // Config
    val bufferSize: Float = 4800f,
    val periodSizeOption: Int = 0, // 0 = Auto
    val engineTypeOption: Int = 0, // 0 = AAudio, 1 = OpenSL, 2 = AudioTrack
    val sampleRateOption: Int = 48000,
    val keepAdbOption: Boolean = false,
    val autoRestartOnOutputChange: Boolean = false,
    val activeDirectionsOption: Int = 1, // 1=Speaker, 2=Mic, 3=Both
    val micSourceOption: Int = 6, // 6=VoiceRec (Default/Auto)
    val notificationEnabled: Boolean = true,
    val showKernelNotice: Boolean = false,
    val keepScreenOnOption: Boolean = false,
    val screensaverEnabled: Boolean = false,
    val screensaverTimeout: Int = 15,
    val screensaverRepositionInterval: Int = 5,
    val screensaverFullscreen: Boolean = true,
    val screensaverActive: Boolean = false,
    val speakerMuted: Boolean = false,
    val micMuted: Boolean = false,

    // Status
    val serviceState: String = "Idle",
    val serviceStateColor: Long = 0xFF888888, // ARGB Long
    val sampleRate: String = "--",
    val periodSize: String = "--",
    val currentBuffer: String = "--",

    // Gadget Status
    val udcController: String = "--",
    val activeFunctions: String = "--",

    // Logs
    val logText: String = "",
    val isLogsExpanded: Boolean = false,

    // Playback device
    val playbackDeviceType: PlaybackDeviceType = PlaybackDeviceType.UNKNOWN
)

class MainActivity : ComponentActivity() {

    private var screensaverFullscreenActive = false // Tracks if fullscreen was enabled when screensaver activated
    private lateinit var windowInsetsController: WindowInsetsController
    private var audioService: AudioService? = null
    private lateinit var settingsRepo: SettingsRepository
    
    // Mutable State Holder
    private var uiState by mutableStateOf(MainUiState())

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
            
            uiState = uiState.copy(
                isServiceRunning = isRunning,
                runningDirections = directions,
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
            screensaverFullscreen = settingsRepo.getScreensaverFullscreen()
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
            } else {
                startServiceAndBind()
            }
        } else {
            startServiceAndBind()
        }

        // Register Receivers
        ContextCompat.registerReceiver(this, logReceiver, IntentFilter(AudioService.ACTION_LOG), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, stateReceiver, IntentFilter(AudioService.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, statsReceiver, IntentFilter(AudioService.ACTION_STATS_UPDATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, gadgetResultReceiver, IntentFilter(AudioService.ACTION_GADGET_RESULT), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, gadgetStatusReceiver, IntentFilter(AudioService.ACTION_GADGET_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)

        startServiceAndBind()

        setContent {
            // Basic Material Theme wrapper
            MaterialTheme(
                colorScheme = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) 
                    dynamicDarkColorScheme(LocalContext.current) else darkColorScheme()
            ) {
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
                            screensaverFullscreen = settingsRepo.getScreensaverFullscreen()
                        )
                    },
                    onToggleLogs = { uiState = uiState.copy(isLogsExpanded = !uiState.isLogsExpanded) }
                )
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
            startServiceAndBind()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    state: MainUiState,
    onToggleGadget: (Boolean) -> Unit,
    onToggleCapture: () -> Unit,
    onBufferSizeChange: (Float) -> Unit,
    onPeriodSizeChange: (Int) -> Unit,
    onEngineTypeChange: (Int) -> Unit,
    onSampleRateChange: (Int) -> Unit,
    onKeepAdbChange: (Boolean) -> Unit,
    onAutoRestartChange: (Boolean) -> Unit,
    onActiveDirectionsChange: (Int) -> Unit,
    onMicSourceChange: (Int) -> Unit,
    onNotificationEnabledChange: (Boolean) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onScreensaverEnabledChange: (Boolean) -> Unit,
    onScreensaverTimeoutChange: (Int) -> Unit,
    onScreensaverRepositionIntervalChange: (Int) -> Unit,
    onScreensaverFullscreenChange: (Boolean) -> Unit,
    onScreensaverActivate: () -> Unit,
    onScreensaverDeactivate: () -> Unit,
    onToggleSpeakerMute: () -> Unit,
    onToggleMicMute: () -> Unit,
    onResetSettings: () -> Unit,
    onToggleLogs: () -> Unit
) {
    val navController = rememberNavController()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Screensaver timer logic
    val screensaverEnabled = state.keepScreenOnOption && state.screensaverEnabled
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Reset timer on navigation
    LaunchedEffect(currentRoute) {
        lastInteractionTime = System.currentTimeMillis()
    }

    val myNestedScrollConnection = remember(scrollBehavior.nestedScrollConnection) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                lastInteractionTime = System.currentTimeMillis()
                return scrollBehavior.nestedScrollConnection.onPreScroll(available, source)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                lastInteractionTime = System.currentTimeMillis()
                return scrollBehavior.nestedScrollConnection.onPostScroll(consumed, available, source)
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(myNestedScrollConnection),
        topBar = {
            if (currentRoute != "about") {
                LargeTopAppBar(
                    title = { Text("USB Audio Bridge") },
                    scrollBehavior = scrollBehavior
                )
            }
        },
        bottomBar = {
            BottomNavBar(navController = navController)
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding)
        ) {
            composable("home") {
                HomeScreen(
                    state = state,
                    onToggleGadget = onToggleGadget,
                    onToggleCapture = onToggleCapture,
                    onToggleSpeakerMute = onToggleSpeakerMute,
                    onToggleMicMute = onToggleMicMute,
                    onToggleLogs = onToggleLogs
                )
            }
            composable("settings") {
                SettingsScreen(
                    state = state,
                    onBufferSizeChange = onBufferSizeChange,
                    onPeriodSizeChange = onPeriodSizeChange,
                    onEngineTypeChange = onEngineTypeChange,
                    onSampleRateChange = onSampleRateChange,
                    onKeepAdbChange = onKeepAdbChange,
                    onAutoRestartChange = onAutoRestartChange,
                    onActiveDirectionsChange = onActiveDirectionsChange,
                    onMicSourceChange = onMicSourceChange,
                    onNotificationEnabledChange = onNotificationEnabledChange,
                    onKeepScreenOnChange = onKeepScreenOnChange,
                    onScreensaverEnabledChange = onScreensaverEnabledChange,
                    onScreensaverTimeoutChange = onScreensaverTimeoutChange,
                    onScreensaverRepositionIntervalChange = onScreensaverRepositionIntervalChange,
                    onScreensaverFullscreenChange = onScreensaverFullscreenChange,
                    onResetSettings = onResetSettings
                )
            }
            composable("about") {
                AboutScreen()
            }
        }
    }

    // Screensaver timer
    LaunchedEffect(screensaverEnabled, state.screensaverTimeout) {
        if (!screensaverEnabled) return@LaunchedEffect
        
        lastInteractionTime = System.currentTimeMillis()  // Reset timer when enabled
        
        while (true) {
            delay(1000) // Check every second
            val timeSinceLastInteraction = System.currentTimeMillis() - lastInteractionTime
            val shouldActivate = timeSinceLastInteraction >= (state.screensaverTimeout * 1000L)
            
            if (shouldActivate && !state.screensaverActive) {
                onScreensaverActivate()
            }
        }
    }
    
    // Screensaver overlay
    if (state.screensaverActive) {
        ScreensaverOverlay(
            state = state,
            onDismiss = {
                onScreensaverDeactivate()
                lastInteractionTime = System.currentTimeMillis()
            }
        )
    }
}

@Composable
fun BottomNavBar(navController: NavController) {
    val items = listOf("Home", "Settings", "About")
    val icons = listOf(Icons.Default.Home, Icons.Default.Settings, Icons.Default.Info)
    val routes = listOf("home", "settings", "about")

    NavigationBar {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route

        items.forEachIndexed { index, item ->
            NavigationBarItem(
                icon = { Icon(icons[index], contentDescription = item) },
                label = { Text(item) },
                selected = currentRoute == routes[index],
                onClick = {
                    navController.navigate(routes[index]) {
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: MainUiState,
    onToggleGadget: (Boolean) -> Unit,
    onToggleCapture: () -> Unit,
    onToggleSpeakerMute: () -> Unit,
    onToggleMicMute: () -> Unit,
    onToggleLogs: () -> Unit
) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Card 1: Main Controls
            item {
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        FilledTonalButton(
                            onClick = { onToggleGadget(!state.isGadgetEnabled) },
                            enabled = !state.isGadgetPending,
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Text(
                                when {
                                    state.isGadgetPending -> if (state.isGadgetEnabled) "Disabling..." else "Enabling..."
                                    state.isGadgetEnabled -> "Disable USB Gadget"
                                    else -> "Enable USB Gadget"
                                }
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onToggleCapture,
                            enabled = state.isGadgetEnabled && !state.isCapturePending,
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            val buttonText = when {
                                state.isCapturePending -> if (state.isServiceRunning) "Stopping..." else "Starting..."
                                state.isServiceRunning -> "Stop Audio Capture"
                                else -> "Start Audio Capture"
                            }
                            Text(buttonText)
                        }
                    }
                }
            }

            // Card 1.5: Audio Devices
            item {
                Text(
                    text = "AUDIO DEVICES",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)
                )
            
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        // Active bridges row
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Active bridges",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (state.isServiceRunning && ((state.runningDirections and 1) != 0 || (state.runningDirections and 2) != 0)) {
                                    Text(
                                        text = "Tap icons to mute/unmute",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                            if (!state.isServiceRunning) {
                                Text(
                                    text = "--",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val isSpeaker = (state.runningDirections and 1) != 0
                                    val isMic = (state.runningDirections and 2) != 0
                                    if (isSpeaker) {
                                        Icon(
                                            painter = painterResource(if (state.speakerMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up),
                                            contentDescription = if (state.speakerMuted) "Unmute Speaker" else "Mute Speaker",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clickable { onToggleSpeakerMute() }
                                        )
                                    }
                                    if (isMic) {
                                        Icon(
                                            painter = painterResource(if (state.micMuted) R.drawable.ic_mic_off else R.drawable.ic_mic),
                                            contentDescription = if (state.micMuted) "Unmute Microphone" else "Mute Microphone",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clickable { onToggleMicMute() }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        // Playback device row, icon aligned right
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Playback device:",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.weight(1f))
                            val iconRes = when (state.playbackDeviceType) {
                                PlaybackDeviceType.BLUETOOTH -> R.drawable.ic_playback_bluetooth
                                PlaybackDeviceType.HEADPHONES -> R.drawable.ic_playback_headphones
                                PlaybackDeviceType.SPEAKER -> R.drawable.ic_playback_speaker
                                else -> null
                            }
                            if (iconRes != null) {
                                Icon(
                                    painter = painterResource(iconRes),
                                    contentDescription = state.playbackDeviceType.name,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Card 2: Status
            item {
                Text(
                    text = "DEVICE STATUS",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)
                )
            
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        StatusRow("State", state.serviceState, Color(state.serviceStateColor))
                        Spacer(Modifier.height(8.dp))
                        StatusRow("Sample rate", state.sampleRate)
                        Spacer(Modifier.height(8.dp))
                        StatusRow("Period size", state.periodSize)
                        Spacer(Modifier.height(8.dp))
                        StatusRow("Current buffer", state.currentBuffer)
                    }
                }
            }

            // Card 3: Gadget Status
            item {
                Text(
                    text = "GADGET STATUS",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)
                )
            
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        StatusRow("Controller", state.udcController)
                        Spacer(Modifier.height(8.dp))
                        StatusRow("Active functions", state.activeFunctions)
                    }
                }
            }

            // Card 4: Logs
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleLogs() }
                        .padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "DEBUG LOGS",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    
                    // Copy Button
                    val context = LocalContext.current
                    IconButton(
                        onClick = { 
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("UsbAudioLogs", state.logText)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp) // Slightly smaller than default
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_copy),
                            contentDescription = "Copy All Logs",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    val rotation by animateFloatAsState(
                        targetValue = if (state.isLogsExpanded) 180f else 0f, 
                        label = "arrowRotation"
                    )
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.rotate(rotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AnimatedVisibility(
                    visible = state.isLogsExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    ElevatedCard(
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
                    ) {
                        val logScroll = rememberScrollState()
                        LaunchedEffect(state.logText) {
                            logScroll.animateScrollTo(logScroll.maxValue)
                        }
                        
                        val nestedScrollInterop = remember {
                            object : NestedScrollConnection {
                                override fun onPostScroll(
                                    consumed: Offset,
                                    available: Offset,
                                    source: NestedScrollSource
                                ): Offset {
                                    return available // Consume remaining scroll to prevent parent (LazyColumn) from getting it
                                }
                                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                                    return available
                                }
                            }
                        }

                        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                             SelectionContainer {
                                 Text(
                                     text = state.logText,
                                     fontFamily = FontFamily.Monospace,
                                     fontSize = 12.sp,
                                     color = MaterialTheme.colorScheme.onSurface,
                                     modifier = Modifier
                                         .nestedScroll(nestedScrollInterop)
                                         .verticalScroll(logScroll)
                                 )
                             }
                        }
                    }
                }
            }
            
            // Bottom Padding
            item {
                Spacer(Modifier.height(32.dp))
            }
        }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: MainUiState,
    onBufferSizeChange: (Float) -> Unit,
    onPeriodSizeChange: (Int) -> Unit,
    onEngineTypeChange: (Int) -> Unit,
    onSampleRateChange: (Int) -> Unit,
    onKeepAdbChange: (Boolean) -> Unit,
    onAutoRestartChange: (Boolean) -> Unit,
    onActiveDirectionsChange: (Int) -> Unit,
    onMicSourceChange: (Int) -> Unit,
    onNotificationEnabledChange: (Boolean) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onScreensaverEnabledChange: (Boolean) -> Unit,
    onScreensaverTimeoutChange: (Int) -> Unit,
    onScreensaverRepositionIntervalChange: (Int) -> Unit,
    onScreensaverFullscreenChange: (Boolean) -> Unit,
    onResetSettings: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Text(
                text = "AUDIO CONFIGURATION",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Buffer Size
        item {
            ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Buffer size", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    
                    val rate = state.sampleRateOption.toFloat()
                    val minBuffer = rate * 0.01f // 10ms
                    val maxBuffer = rate * 0.5f  // 500ms
                    
                    val ms = (state.bufferSize / (rate / 1000f)).toInt()
                    Text(
                        text = "${state.bufferSize.toInt()} frames (~${ms}ms)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Slider(
                        value = state.bufferSize.coerceIn(minBuffer, maxBuffer),
                        onValueChange = onBufferSizeChange,
                        valueRange = minBuffer..maxBuffer,
                        steps = 48 // 10ms increments (10..500ms)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Lower Latency (10ms)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = "Higher Stability (500ms)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }



        // Active Directions (Devices)
        item {
            ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Audio devices", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Select which devices to enable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val isSpeaker = (state.activeDirectionsOption and 1) != 0
                        val isMic = (state.activeDirectionsOption and 2) != 0
                        
                        FilterChip(
                            selected = isSpeaker,
                            onClick = { 
                                val newMask = if (isSpeaker) state.activeDirectionsOption and 1.inv() else state.activeDirectionsOption or 1
                                // Prevent disabling both? User said "enable/disable either as they please". 
                                // But having NO devices makes bridge useless. Let's allow it but maybe warn? 
                                // Or simply allow it (will just idle).
                                onActiveDirectionsChange(newMask)
                            },
                            label = { Text("Speaker (Output)") },
                            leadingIcon = { 
                                if (isSpeaker) Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) 
                            }
                        )
                        
                        FilterChip(
                            selected = isMic,
                            onClick = { 
                                val newMask = if (isMic) state.activeDirectionsOption and 2.inv() else state.activeDirectionsOption or 2
                                onActiveDirectionsChange(newMask)
                            },
                            label = { Text("Mic (Input)") },
                            leadingIcon = {
                                if (isMic) Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        )
                    }
                }
            }
        }

        // Mic Source
        item {
            var showMicDialog by remember { mutableStateOf(false) }
            val options = listOf(6, 1, 5, 7, 9, 10)
            val labels = listOf("Auto (voice rec)", "Mic", "Camcorder", "Voice comm", "Unprocessed", "Performance")
            
            ElevatedCard(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().clickable { showMicDialog = true }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Microphone source", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Select input preset. Affects processing (echo cancellation, noise suppression).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    val index = options.indexOf(state.micSourceOption)
                    val label = if (index >= 0) labels[index] else "Unknown"
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }
            }
            
            if (showMicDialog) {
                SelectionDialog(
                    title = "Microphone source",
                    options = options,
                    labels = labels,
                    selectedOption = state.micSourceOption,
                    onDismiss = { showMicDialog = false },
                    onOptionSelected = { 
                        onMicSourceChange(it)
                        showMicDialog = false
                    }
                )
            }
        }

        // Sample Rate
        item {
            var showSampleRateDialog by remember { mutableStateOf(false) }
            val rates = listOf(22050, 32000, 44100, 48000, 88200, 96000, 192000)
            val labels = rates.map { "$it Hz" }

            ElevatedCard(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().clickable { showSampleRateDialog = true }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Sample rate", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "48kHz is standard for Android. Higher rates increase CPU load and may require larger buffers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Text(
                        text = "${state.sampleRateOption} Hz",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }
            }

            if (showSampleRateDialog) {
                SelectionDialog(
                    title = "Sample Rate",
                    options = rates,
                    labels = labels,
                    selectedOption = state.sampleRateOption,
                    onDismiss = { showSampleRateDialog = false },
                    onOptionSelected = { 
                        onSampleRateChange(it)
                        showSampleRateDialog = false
                    },
                    headerContent = {
                        Column {
                            Text(
                                text = "Changing this requires restarting/resetting the USB Gadget.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Higher sample rates (e.g. 96kHz) increase CPU load significantly. You may need to increase the Buffer Size to prevent audio overruns.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                             HorizontalDivider()
                             Spacer(Modifier.height(12.dp))
                        }
                    }
                )
            }
        }

        // Output Engine
        item {
            ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Audio output engine", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Select the backend driver for playback.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = state.engineTypeOption == 0,
                            onClick = { onEngineTypeChange(0) },
                            label = { Text("AAudio") }
                        )
                        FilterChip(
                            selected = state.engineTypeOption == 1,
                            onClick = { onEngineTypeChange(1) },
                            label = { Text("OpenSL ES") }
                        )
                        FilterChip(
                            selected = state.engineTypeOption == 2,
                            onClick = { onEngineTypeChange(2) },
                            label = { Text("AudioTrack") }
                        )
                    }
                    
                    Spacer(Modifier.height(12.dp))
                    val desc = when(state.engineTypeOption) {
                        0 -> "AAudio: Low latency, high performance. Recommended for Android 8.1+."
                        1 -> "OpenSL ES: Native audio standard. Good alternative if AAudio has glitches."
                        2 -> "AudioTrack: Legacy Java-based audio. Highest compatibility, higher latency."
                        else -> ""
                    }
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Period Size
        item {
            var showPeriodDialog by remember { mutableStateOf(false) }
            val options = listOf(0, 4096, 2048, 1024, 480, 240, 120, 64)
            val labels = listOf("Auto", "4096", "2048", "1024", "480", "240", "120", "64")
            
            ElevatedCard(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().clickable { showPeriodDialog = true }
            ) {
                 Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Period size (frames)", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Controls capture latency and CPU load.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    val index = options.indexOf(state.periodSizeOption)
                    val label = if (index >= 0) labels[index] else state.periodSizeOption.toString()
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }
            }
            
            if (showPeriodDialog) {
                SelectionDialog(
                    title = "Period size (frames)",
                    options = options,
                    labels = labels,
                    selectedOption = state.periodSizeOption,
                    onDismiss = { showPeriodDialog = false },
                    onOptionSelected = {
                        onPeriodSizeChange(it)
                        showPeriodDialog = false
                    }
                )
            }
        }
        
        // USB Settings
        item {
            Text(
                text = "USB SETTINGS",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }

        item {
            ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Keep ADB enabled",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Forces ADB to remain active (Composite Gadget). May not work on some devices..",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.keepAdbOption,
                            onCheckedChange = onKeepAdbChange
                        )
                    }
                }
            }
        }

        // Audio Behavior
        item {
            Text(
                text = "AUDIO BEHAVIOR",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }

        item {
            ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Always continue on output change",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Keep playing when any output change occurs, including when headphones or Bluetooth are disconnected. When disabled, behaves like music apps (stops on disconnect).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.autoRestartOnOutputChange,
                            onCheckedChange = onAutoRestartChange
                        )
                    }
                }
            }
        }

        // Notification
        item {
            Text(
                text = "NOTIFICATION",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }

        item {
            ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Enable interactive notification",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Enable persistent status notification with controls.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.notificationEnabled,
                            onCheckedChange = onNotificationEnabledChange
                        )
                    }
                }
            }
        }

        // Display
        item {
            Text(
                text = "DISPLAY",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }

        item {
            ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Keep screen on",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Prevent the screen from turning off while the app is open. Might be useful if audio lags when screen is off.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.keepScreenOnOption,
                            onCheckedChange = onKeepScreenOnChange
                        )
                    }
                }
            }
        }

        // Screensaver
        item {
            ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Enable screensaver",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Show a screensaver to prevent burn-in on OLED displays and image retention on LCDs. Only available when 'Keep screen on' is enabled.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.screensaverEnabled,
                            onCheckedChange = onScreensaverEnabledChange,
                            enabled = state.keepScreenOnOption
                        )
                    }
                    
                    if (state.screensaverEnabled && state.keepScreenOnOption) {
                        Spacer(Modifier.height(12.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Timeout: ${state.screensaverTimeout}s",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            
                            Slider(
                                value = ((state.screensaverTimeout - 5) / 5).toFloat(),
                                onValueChange = { val snapped = it.roundToInt(); val timeout = 5 + snapped * 5; onScreensaverTimeoutChange(timeout) },
                                valueRange = 0f..11f,
                                steps = 11,
                                modifier = Modifier.weight(2f)
                            )
                        }
                        
                        Spacer(Modifier.height(12.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Reposition: ${state.screensaverRepositionInterval}s",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            
                            Slider(
                                value = ((state.screensaverRepositionInterval - 5) / 5).toFloat(),
                                onValueChange = { val snapped = it.roundToInt(); val interval = 5 + snapped * 5; onScreensaverRepositionIntervalChange(interval) },
                                valueRange = 0f..5f,
                                steps = 5,
                                modifier = Modifier.weight(2f)
                            )
                        }
                        
                        Spacer(Modifier.height(12.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Fullscreen mode",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Hide system UI elements when screensaver is active",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = state.screensaverFullscreen,
                                onCheckedChange = onScreensaverFullscreenChange
                            )
                        }
                    }
                }
            }
        }

        // Reset
        item {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onResetSettings,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Reset to defaults")
            }
        }
    }
}

@Composable
fun ScreensaverOverlay(
    state: MainUiState,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    
    var contentSize by remember { mutableStateOf<IntSize>(IntSize.Zero) }
    
    var isPositioned by remember(state.screensaverActive) { mutableStateOf(false) }
    
    // Start centered (approximate, will be corrected when content size is known)
    var position by remember(state.screensaverActive) {
        val screenWidth = context.resources.displayMetrics.widthPixels.toFloat()
        val screenHeight = context.resources.displayMetrics.heightPixels.toFloat()
        val approxContentWidth = 300f
        val approxContentHeight = 200f
        val centerX = maxOf(0f, (screenWidth - approxContentWidth) / 2f)
        val centerY = maxOf(0f, (screenHeight - approxContentHeight) / 2f)
        mutableStateOf(Pair(centerX, centerY))
    }
    
    LaunchedEffect(contentSize) {
        if (contentSize != IntSize.Zero) {
            val screenWidth = context.resources.displayMetrics.widthPixels.toFloat()
            val screenHeight = context.resources.displayMetrics.heightPixels.toFloat()
            
            val centerX = maxOf(0f, (screenWidth - contentSize.width) / 2f)
            val centerY = maxOf(0f, (screenHeight - contentSize.height) / 2f)
            
            position = Pair(centerX, centerY)
            isPositioned = true
        }
    }
    
    // Random repositioning
    LaunchedEffect(state.screensaverRepositionInterval) {
        while (true) {
            delay(state.screensaverRepositionInterval * 1000L)
            if (contentSize != IntSize.Zero) {
                val screenWidth = context.resources.displayMetrics.widthPixels.toFloat()
                val screenHeight = context.resources.displayMetrics.heightPixels.toFloat()
                
                val maxX = maxOf(0f, screenWidth - contentSize.width.toFloat())
                val maxY = maxOf(0f, screenHeight - contentSize.height.toFloat())
                
                val randomX = kotlin.random.Random.nextFloat() * maxX
                val randomY = kotlin.random.Random.nextFloat() * maxY
                
                position = Pair(randomX, randomY)
            }
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { onDismiss() }
    ) {
        // Content positioned randomly
        Column(
            modifier = Modifier
                .offset { if (isPositioned) IntOffset(position.first.toInt(), position.second.toInt()) else IntOffset(-9999, -9999) }
                .onSizeChanged { contentSize = it }
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Logo
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_usb),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(18.dp)
                        .fillMaxSize(),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            
            // Title
            Text(
                "USB Audio Bridge",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            
            // State
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "State: ",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Text(
                    state.serviceState,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(state.serviceStateColor),
                    textAlign = TextAlign.Center
                )
            }
            
            // Sample rate
            Text(
                "Sample rate: ${state.sampleRate}",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                textAlign = TextAlign.Center
            )

                // Bridge icons (centered, show only active bridges)
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    val isSpeaker = (state.runningDirections and 1) != 0
                    val isMic = (state.runningDirections and 2) != 0
                    if (isSpeaker) {
                        Icon(
                            painter = painterResource(if (state.speakerMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up),
                            contentDescription = if (state.speakerMuted) "Unmute Speaker" else "Mute Speaker",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (isSpeaker && isMic) {
                        Spacer(Modifier.width(4.dp))
                    }
                    if (isMic) {
                        Icon(
                            painter = painterResource(if (state.micMuted) R.drawable.ic_mic_off else R.drawable.ic_mic),
                            contentDescription = if (state.micMuted) "Unmute Microphone" else "Mute Microphone",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Playback device icon (centered, below bridge icons, no text)
                val playbackIconRes = when (state.playbackDeviceType) {
                    PlaybackDeviceType.BLUETOOTH -> R.drawable.ic_playback_bluetooth
                    PlaybackDeviceType.HEADPHONES -> R.drawable.ic_playback_headphones
                    PlaybackDeviceType.SPEAKER -> R.drawable.ic_playback_speaker
                    else -> null
                }
                if (playbackIconRes != null) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Icon(
                            painter = painterResource(playbackIconRes),
                            contentDescription = state.playbackDeviceType.name,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
        }
    }
}

@Composable
fun AboutScreen() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        item {
            Spacer(Modifier.height(32.dp))
            
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(96.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_usb),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxSize(),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            
            Spacer(Modifier.height(16.dp))
            
            Text(
                "USB Audio Bridge",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Text(
                "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            
            Text(
                "Build ${BuildConfig.GIT_HASH}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // Description Card
        item {
            ElevatedCard(
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Transform your rooted Android device into a USB sound card for any computer or host device.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    Spacer(Modifier.height(12.dp))
                    
                    Text(
                        "Uses the Linux kernel's USB Gadget subsystem to expose a UAC2 (USB Audio Class 2.0) device, capturing audio from the host and playing it through your phone's speakers or connected audio devices.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        // Kernel Compatibility Notice
        item {
            ElevatedCard(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Kernel compatibility",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                    
                    Spacer(Modifier.height(12.dp))
                    
                    Text(
                        "Devices with kernels older than Linux 5.10 may require a custom kernel build with CONFIG_USB_CONFIGFS_F_UAC2=y enabled.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    
                    Spacer(Modifier.height(8.dp))
                    
                    Text(
                        "Google did not include UAC2 gadget support as standard until GKI 2.0 (Android 12, kernel 5.10+). If the app fails to enable the gadget, this is likely the cause.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }
        
        // Libraries Used
        item {
            ElevatedCard(
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "Libraries & technologies",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    Spacer(Modifier.height(12.dp))
                    
                    AboutLibraryRow("TinyALSA", "Lightweight ALSA library for PCM capture")
                    AboutLibraryRow("AAudio", "Android's high-performance audio API")
                    AboutLibraryRow("OpenSL ES", "Cross-platform audio API for embedded systems")
                    AboutLibraryRow("AudioTrack", "Android's legacy audio playback API")
                    AboutLibraryRow("Linux USB Gadget", "Kernel subsystem for USB device emulation")
                }
            }
        }
        
        // License & Copyright
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                "Licensed under GNU General Public License v3.0",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "© 2026 Flopster101",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // Bottom padding
        item {
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun AboutLibraryRow(name: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(16.dp)
        )
        Column {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun KernelNoticeDialog(onDismiss: (Boolean) -> Unit) {
    var dontShowAgain by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = { onDismiss(dontShowAgain) },
        icon = {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text("Kernel compatibility notice")
        },
        text = {
            Column {
                Text(
                    "This app requires USB Gadget UAC2 support in your device's kernel.",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Spacer(Modifier.height(12.dp))
                
                Text(
                    "Devices with kernels older than Linux 5.10 may need a custom kernel build with CONFIG_USB_CONFIGFS_F_UAC2=y enabled.",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Spacer(Modifier.height(12.dp))
                
                Text(
                    "Google did not include UAC2 gadget support as standard until GKI 2.0 (Android 12, kernel 5.10+).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(Modifier.height(16.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { dontShowAgain = !dontShowAgain }
                ) {
                    Checkbox(
                        checked = dontShowAgain,
                        onCheckedChange = { dontShowAgain = it },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Don't show again",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(dontShowAgain) }) {
                Text("Dismiss")
            }
        }
    )
}

@Composable
fun StatusRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}

@Composable
fun <T> SelectionDialog(
    title: String,
    options: List<T>,
    labels: List<String>,
    selectedOption: T,
    onDismiss: () -> Unit,
    onOptionSelected: (T) -> Unit,
    headerContent: @Composable (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (headerContent != null) {
                    headerContent()
                }
                options.forEachIndexed { index, option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOptionSelected(option) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = option == selectedOption,
                            onClick = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = labels[index],
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
