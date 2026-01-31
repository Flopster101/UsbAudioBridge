# USB Audio Bridge

A simple tool to turn your Android device into a USB Soundcard for any USB host device (like your PC, or even another phone/tablet!). No program/app/driver is required on the other side!

## Usage
1.  **Root Check:** Requires **KernelSU or Magisk** (and a kernel with ConfigFS + UAC2 support).
2.  **Setup gadget:** Open the app and tap "Enable USB Gadget".
3.  **Connect:** Plug your device into the host (e.g. PC).
4.  **Start:** Tap on "Start Audio Capture" to start the audio bridge.

## Features
*   **Plug & play:** Emulates a standard USB Audio Class 2.0 device.
*   **Low latency:** Bridges internal AAudio to the USB gadget via TinyALSA.
*   **Service-based:** Runs in the foreground to keep audio alive.
*   **Bridge input and output:** Your Android device can act as a microphone and a speaker at the same time! 

#### TODOs

- [ ] Test Magisk and Apatch support (implemented, untested)
- [ ] Improve performance (crackling is present on older devices)
- [ ] Support more intrusive USB Gadget HALs (maybe disable HAL before enabling gadget?)
- [x] Notice for when root is not found (currently crashes app)
- [ ] Properly restore the previous gadget state accurately after disabling

## License
GNU GPLv3
