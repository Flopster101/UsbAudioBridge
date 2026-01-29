package com.flopster101.usbaudiobridge

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.ClipboardManager
import android.content.ClipData
import android.widget.Toast

// UI State Definition
data class MainUiState(
    val isGadgetEnabled: Boolean = false,
    val isServiceRunning: Boolean = false, // Streaming active?
    val isAppBound: Boolean = false,       // Service bound?
    
    // Config
    val bufferSize: Float = 4800f,
    val periodSizeOption: Int = 0, // 0 = Auto
    val engineTypeOption: Int = 0, // 0 = AAudio, 1 = OpenSL
    val sampleRateOption: Int = 48000,

    // Status
    val serviceState: String = "Idle",
    val serviceStateColor: Long = 0xFF888888, // ARGB Long
    val sampleRate: String = "--",
    val periodSize: String = "--",
    val currentBuffer: String = "--",

    // Logs
    val logText: String = "",
    val isLogsExpanded: Boolean = false
)

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("UsbAudioSettings", Context.MODE_PRIVATE)

    fun saveBufferSize(size: Float) = prefs.edit().putFloat("buffer_size", size).apply()
    fun getBufferSize(): Float = prefs.getFloat("buffer_size", 4800f)

    fun savePeriodSize(size: Int) = prefs.edit().putInt("period_size", size).apply()
    fun getPeriodSize(): Int = prefs.getInt("period_size", 0)

    fun saveEngineType(type: Int) = prefs.edit().putInt("engine_type", type).apply()
    fun getEngineType(): Int = prefs.getInt("engine_type", 0)

    fun resetDefaults() {
        prefs.edit().clear().apply()
    }
}

class MainActivity : ComponentActivity() {

    private var audioService: AudioService? = null
    private lateinit var settingsRepo: SettingsRepository
    
    // Mutable State Holder
    private var uiState by mutableStateOf(MainUiState())

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
            
            uiState = uiState.copy(
                isServiceRunning = isRunning,
                serviceState = label ?: if (isRunning) "Active" else "Stopped",
                serviceStateColor = if (color != null && color != 0L) color else if (isRunning) 0xFFFFC107 else 0xFF888888
            )

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

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as AudioService.LocalBinder
            audioService = binder.getService()
            uiState = uiState.copy(isAppBound = true)
            appendLog("[App] Service connected")
            restoreUiState()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            uiState = uiState.copy(isAppBound = false)
            audioService = null
            appendLog("[App] Service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        settingsRepo = SettingsRepository(this)
        // Load settings
        uiState = uiState.copy(
            bufferSize = settingsRepo.getBufferSize(),
            periodSizeOption = settingsRepo.getPeriodSize(),
            engineTypeOption = settingsRepo.getEngineType()
        )
        
        // Start Service
        val intent = Intent(this, AudioService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        
        // Register Receivers
        LocalBroadcastManager.getInstance(this).registerReceiver(logReceiver, IntentFilter(AudioService.ACTION_LOG))
        LocalBroadcastManager.getInstance(this).registerReceiver(stateReceiver, IntentFilter(AudioService.ACTION_STATE_CHANGED))
        LocalBroadcastManager.getInstance(this).registerReceiver(statsReceiver, IntentFilter(AudioService.ACTION_STATS_UPDATE))

        setContent {
            // Basic Material Theme wrapper
            MaterialTheme(
                colorScheme = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) 
                    dynamicDarkColorScheme(LocalContext.current) else darkColorScheme()
            ) {
                AppNavigation(
                    state = uiState,
                    onToggleGadget = { enable ->
                         if (enable) {
                             audioService?.enableGadget()
                             uiState = uiState.copy(isGadgetEnabled = true)
                         } else {
                             audioService?.stopBridge()
                             uiState = uiState.copy(isGadgetEnabled = false, isServiceRunning = false)
                         }
                    },
                    onToggleCapture = {
                        if (uiState.isServiceRunning) {
                             audioService?.stopAudioOnly()
                        } else {
                             audioService?.startBridge(uiState.bufferSize.toInt(), uiState.periodSizeOption, uiState.engineTypeOption)
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
                    onResetSettings = {
                        settingsRepo.resetDefaults()
                        uiState = uiState.copy(
                            bufferSize = settingsRepo.getBufferSize(),
                            periodSizeOption = settingsRepo.getPeriodSize(),
                            engineTypeOption = settingsRepo.getEngineType()
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
                     
                     if (service.isBridgeRunning) {
                         uiState = uiState.copy(isServiceRunning = true)
                         appendLog("[App] Restored connection to active stream")
                     }
                 }
             }.start()
        }
    }

    private fun appendLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$time] $msg\n"
        
        var currentText = uiState.logText + line
        // Circular Buffer Logic
        if (currentText.length > 5000) {
            val excess = currentText.length - 3000
            val cutIndex = currentText.indexOf('\n', excess)
            if (cutIndex != -1) {
                currentText = currentText.substring(cutIndex + 1)
            }
        }
        uiState = uiState.copy(logText = currentText)
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(logReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statsReceiver)
        if (uiState.isAppBound) unbindService(connection)
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
    onResetSettings: () -> Unit,
    onToggleLogs: () -> Unit
) {
    val navController = rememberNavController()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("USB Audio Bridge") },
                scrollBehavior = scrollBehavior
            )
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
                    onToggleLogs = onToggleLogs
                )
            }
            composable("settings") {
                SettingsScreen(
                    state = state,
                    onBufferSizeChange = onBufferSizeChange,
                    onPeriodSizeChange = onPeriodSizeChange,
                    onEngineTypeChange = onEngineTypeChange,
                    onResetSettings = onResetSettings
                )
            }
            composable("about") {
                AboutScreen()
            }
        }
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
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Text(if (state.isGadgetEnabled) "Disable USB Gadget" else "Enable USB Gadget")
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onToggleCapture,
                            enabled = state.isGadgetEnabled,
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Text(if (state.isServiceRunning) "Stop Audio Capture" else "Start Audio Capture")
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
                        StatusRow("Sample Rate", state.sampleRate)
                        Spacer(Modifier.height(8.dp))
                        StatusRow("Period Size", state.periodSize)
                        Spacer(Modifier.height(8.dp))
                        StatusRow("Current Buffer", state.currentBuffer)
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
                            imageVector = Icons.Default.ContentCopy,
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

@Composable
fun SettingsScreen(
    state: MainUiState,
    onBufferSizeChange: (Float) -> Unit,
    onPeriodSizeChange: (Int) -> Unit,
    onEngineTypeChange: (Int) -> Unit,
    onResetSettings: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Text(
                text = "Audio Configuration",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Buffer Size
        item {
            ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Buffer Size", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${state.bufferSize.toInt()} frames (~${(state.bufferSize / 48).toInt()}ms)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Slider(
                        value = state.bufferSize,
                        onValueChange = onBufferSizeChange,
                        valueRange = 480f..9600f,
                        steps = 18
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Lower Latency",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = "Higher Stability",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }

        // Sample Rate
        item {
            ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Sample Rate", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "48000 Hz",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Fixed (Standard for USB Audio)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        // Output Engine
        item {
            ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Audio Output Engine", style = MaterialTheme.typography.titleMedium)
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
            ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Period Size (Frames)", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Controls capture latency and CPU load.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    
                    val options = listOf(0, 1024, 480, 240, 120, 64)
                    val labels = listOf("Auto", "1024", "480", "240", "120", "64")
                    
                    // Simple FlowRow equivalent or Scrollable Row
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        options.forEachIndexed { index, value ->
                            val isSelected = state.periodSizeOption == value
                            FilterChip(
                                selected = isSelected,
                                onClick = { onPeriodSizeChange(value) },
                                label = { Text(labels[index]) }
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
                Text("Reset to Defaults")
            }
        }
    }
}

@Composable
    fun AboutScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("About UsbAudioBridge", style = MaterialTheme.typography.headlineMedium)
        Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_HASH})", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text("A simple tool to bridge USB audio gadgets with AAudio.", 
             style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant,
             modifier = Modifier.padding(32.dp),
             textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
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
