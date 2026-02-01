package com.flopster101.usbaudiobridge

data class MainUiState(
    val isGadgetEnabled: Boolean = false,
    val isGadgetPending: Boolean = false,  // Gadget operation in progress?
    val isServiceRunning: Boolean = false, // Streaming active?
    val isCapturePending: Boolean = false, // Capture operation in progress?
    val runningDirections: Int = 0,        // Active directions reported by service
    val isAppBound: Boolean = false,       // Service bound?
    
    // Config
    val bufferSize: Float = 4800f,
    val bufferMode: Int = 0, // 0 = Simple (Presets), 1 = Advanced (Slider)
    val latencyPreset: Int = 2, // 2 = Normal
    val periodSizeOption: Int = 0, // 0 = Auto
    val engineTypeOption: Int = 0, // 0 = AAudio, 1 = OpenSL, 2 = AudioTrack
    val sampleRateOption: Int = 48000,
    val keepAdbOption: Boolean = false,
    val autoRestartOnOutputChange: Boolean = false,
    val activeDirectionsOption: Int = 1, // 1=Speaker, 2=Mic, 3=Both
    val micSourceOption: Int = 6, // 6=VoiceRec (Default/Auto)
    val notificationEnabled: Boolean = true,
    val showKernelNotice: Boolean = false,
    val showOldKernelNotice: Boolean = false,
    val showNoUac2Error: Boolean = false,
    val keepScreenOnOption: Boolean = false,
    val screensaverEnabled: Boolean = false,
    val screensaverTimeout: Int = 15,
    val screensaverRepositionInterval: Int = 5,
    val screensaverFullscreen: Boolean = true,
    val screensaverActive: Boolean = false,
    val speakerMuted: Boolean = false,
    val micMuted: Boolean = false,
    val muteOnMediaButton: Boolean = true,

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
