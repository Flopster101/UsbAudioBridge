package com.flopster101.usbaudiobridge

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
                        "Uses the Linux kernel's USB Gadget subsystem to expose a UAC2 (USB Audio Class 2.0) device by default, with optional UAC1 compatibility mode for older hosts.",
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
                        "UAC2 mode requires CONFIG_USB_CONFIGFS_F_UAC2=y.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )

                    Spacer(Modifier.height(4.dp))

                    Text(
                        "UAC1 compatibility mode requires CONFIG_USB_CONFIGFS_F_UAC1=y.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        "UAC2 has been available in Linux since 3.18, but Android commonly enables it by default from GKI 2.0 (Android 12, kernel 5.10+).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                    )

                    Spacer(Modifier.height(4.dp))

                    Text(
                        "UAC1 is available on much kernels, but is usually not enabled by default (including GKI). On older kernels, try UAC1 mode first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // Dependencies
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Dependencies",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp)
                )

                Spacer(Modifier.height(12.dp))

                // Open Source Libraries
                ElevatedCard(
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            "Open source libraries",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                        HorizontalDivider()
                        OpenSourceLibraryRow(
                            name = "TinyALSA",
                            description = "Lightweight ALSA library used for low-level PCM capture.",
                            license = "BSD-3-Clause",
                            url = "https://github.com/tinyalsa/tinyalsa"
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Framework & Engine
                ElevatedCard(
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            "Framework & engine",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                        FrameworkRow("AAudio", "Android's high-performance native audio API.")
                        HorizontalDivider()
                        FrameworkRow("OpenSL ES", "Cross-platform audio API for embedded systems.")
                        HorizontalDivider()
                        FrameworkRow("AudioTrack", "Android's Java audio playback API.")
                        HorizontalDivider()
                        FrameworkRow("Linux USB Gadget", "Linux kernel subsystem for USB device emulation.")
                    }
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
    }
}

@Composable
private fun OpenSourceLibraryRow(name: String, description: String, license: String, url: String) {
    val context = LocalContext.current
    ListItem(
        headlineContent = { Text(name, style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(percent = 50),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    tonalElevation = 0.dp
                ) {
                    Text(
                        text = license,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        context.startActivity(intent)
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "$name repository",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun FrameworkRow(name: String, description: String) {
    ListItem(
        headlineContent = { Text(name, style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
