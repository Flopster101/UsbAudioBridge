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

        // Prefer Bluetooth if active
        if (audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn) {
            return PlaybackDeviceType.BLUETOOTH
        }

        // Then wired headset
        if (audioManager.isWiredHeadsetOn) {
            return PlaybackDeviceType.HEADPHONES
        }

        // Then speaker
        if (audioManager.isSpeakerphoneOn) {
            return PlaybackDeviceType.SPEAKER
        }

        // Fallback: scan devices for any connected outputs
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> return PlaybackDeviceType.BLUETOOTH
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> return PlaybackDeviceType.HEADPHONES
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> return PlaybackDeviceType.SPEAKER
            }
        }
        return PlaybackDeviceType.UNKNOWN
    }
}
