# 🖱️ Android Mouse

[![Android API 30+](https://img.shields.io/badge/API-30%2B-brightgreen.svg?style=flat)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.8.0-blue.svg?logo=kotlin)](#)

**Turn your Android device into a hardware-free trackpad for external displays.**

Have you ever plugged your phone into an external monitor for Desktop Mode, only to realize you left your Bluetooth mouse at home? **Android Mouse** is the solution. It transforms your device's screen into a responsive, highly-performant trackpad that natively controls an on-screen cursor on secondary displays.

No root required. Native Android APIs. Pure Kotlin.

## ✨ Features

- **Zero-Latency Movement:** Built on a highly performant `SharedFlow` Coroutines Event Bus.
- **Native Implementation:** Uses built-in Android Overlay and Accessibility APIs—no sketchy third-party input injectors.
- **External Display Support:** Specifically designed to route gestures and render UI on external hardware/monitors via `DisplayManager`.
- **System-Wide Clicks:** Injects standard swipe and tap events directly into the Android framework.

## 🏗️ How it Works

The magic happens across three decoupled, high-performance components:

1. **Trackpad Activity:** A blank, immersive-mode full-screen surface that calculates precise X/Y finger deltas and detects quick tap gestures.
2. **Cursor Overlay:** A specialized `SYSTEM_ALERT_WINDOW` scoped to the secondary display that smoothly renders and tracks a floating cursor in real-time.
3. **Click Injector:** An `AccessibilityService` that observes tap commands from the Event Bus and dispatches click gestures directly to the external monitor framework.


## 🔒 Device Permissions

Because this application simulates mouse input and overlays UI on an external screen, Android restricts it. **You must manually grant these permissions before the application will work:**

1. **Draw Over Other Apps:** Open Settings > Apps > Special app access > **Display over other apps**. Find "Android Mouse" and toggle it **ON**.
2. **Accessibility Access:** Open Settings > Accessibility > Downloaded apps (or Installed services). Find "Android Mouse" and toggle it to **ON**.
3. **Ready to go!** Ensure your device is connected to an external monitor (Desktop Mode) and launch the main Trackpad Activity to start controlling your screen.

---
*Built for the future of mobile productivity.*
