package com.flopster101.usbaudiobridge

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

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
