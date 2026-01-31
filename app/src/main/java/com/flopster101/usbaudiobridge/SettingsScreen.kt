package com.flopster101.usbaudiobridge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

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
    onMuteOnMediaButtonChange: (Boolean) -> Unit,
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
                        Spacer(Modifier.width(16.dp))
                        Switch(
                            checked = state.autoRestartOnOutputChange,
                            onCheckedChange = onAutoRestartChange
                        )
                    }
                }
            }
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
                                text = "Control via Headset buttons",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Use headset/Bluetooth Play/Pause buttons to mute/unmute the speaker bridge.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Switch(
                            checked = state.muteOnMediaButton,
                            onCheckedChange = onMuteOnMediaButtonChange
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
