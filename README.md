# USB Audio Bridge

A simple tool to turn your Android device into a USB Soundcard for any USB host device (like your PC, or even another phone/tablet!). No program/app/driver is required on the other side!

## Usage
1.  **Root Check:** Requires **KernelSU** (and a kernel with ConfigFS + UAC2 support).
2.  **Connect:** Plug your device into the PC.
3.  **Start:** Open the app and tap "Enable USB Audio".

## Features
*   **Plug & play:** Emulates a standard USB Audio Class 2.0 device.
*   **Low latency:** Bridges internal AAudio to the USB gadget via TinyALSA.
*   **Service-based:** Runs in the foreground to keep audio alive.

## License
GNU GPLv3
