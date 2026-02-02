package com.flopster101.usbaudiobridge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
fun AboutLibraryRow(name: String, description: String) {
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
fun OldKernelNoticeDialog(onDismiss: (Boolean) -> Unit) {
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
            Text("Windows audio setup required")
        },
        text = {
            Column {
                Text(
                    "Your device kernel version is older than 5.4. Windows might detect this gadget as an 'Internal AUX port' and disable it by default.",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Spacer(Modifier.height(12.dp))
                
                Text(
                    "If audio doesn't work, please open the Sound Control Panel in Windows, find the new device (it might be disabled), and enable it manually.",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Spacer(Modifier.height(12.dp))
                
                Text(
                    "Note: If you are using 'FloppyKernel', this issue is already fixed and you can ignore this.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
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
fun NoUac2SupportDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text("Kernel support missing")
        },
        text = {
            Column {
                Text(
                    "Setup failed because your device's kernel does not support UAC2 (USB Audio Class 2).",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Spacer(Modifier.height(12.dp))
                
                Text(
                    "This is normal in older kernels (<=4.19). To fix this, the kernel must be rebuilt with:",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Text(
                    "CONFIG_USB_CONFIGFS_F_UAC2=y",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                
                Text(
                    "Rebuild it yourself or ask your kernel/ROM maintainer to do it.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun GadgetSetupFailedDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text("Gadget setup failed")
        },
        text = {
            Column {
                Text(
                    "The USB gadget could not be configured.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    "This can happen if the selected sample rate is not supported by the kernel, due to a kernel bug, or because this device is not yet supported.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    "Try a different sample rate. If none of them work, please report the issue in the repository with logs.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun KeepAdbFailedDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text("Keep ADB not supported")
        },
        text = {
            Column {
                Text(
                    "This device can't keep ADB enabled while the USB gadget is active.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    "Please disable the Keep ADB option and try again.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
