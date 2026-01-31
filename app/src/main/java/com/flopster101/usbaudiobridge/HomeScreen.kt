package com.flopster101.usbaudiobridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
