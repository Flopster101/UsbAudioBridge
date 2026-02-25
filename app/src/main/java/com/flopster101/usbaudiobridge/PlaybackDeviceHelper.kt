package com.flopster101.usbaudiobridge

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

enum class PlaybackDeviceType {
    SPEAKER, HEADPHONES, BLUETOOTH, UNKNOWN
}

object PlaybackDeviceHelper {
    fun getCurrentPlaybackDevice(context: Context): PlaybackDeviceType {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var hasHeadphones = false
        var hasSpeaker = false

        // Prefer Bluetooth first, then wired/USB outputs, then speaker.
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                    return PlaybackDeviceType.BLUETOOTH
                }
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> {
                    hasHeadphones = true
                }
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> {
                    hasSpeaker = true
                }
            }
        }

        if (hasHeadphones) return PlaybackDeviceType.HEADPHONES
        if (hasSpeaker) return PlaybackDeviceType.SPEAKER

        return PlaybackDeviceType.UNKNOWN
    }
}
