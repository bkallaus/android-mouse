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
2. **Cursor Overlay:** A `TYPE_ACCESSIBILITY_OVERLAY` window scoped to the secondary display that smoothly renders and tracks a floating cursor in real-time.
3. **Click Injector:** An `AccessibilityService` that observes tap commands from the Event Bus and dispatches click gestures directly to the external monitor framework.


## 👆 Gestures & Controls

| Input | Action |
|---|---|
| One-finger move | Move cursor (with optional speed-based acceleration) |
| Tap | Left click |
| Two-finger tap | Right click (long-press / context menu) |
| Two-finger drag | Scroll (direction can be reversed in Settings) |
| Press and hold, then drag | Click-and-drag (select text, move items) |
| Back / Home / Recents buttons | System navigation on the external display |
| Keyboard button | Type text; text is inserted at the caret. Send on an empty field = Enter, backspace on an empty field deletes on the target |

Settings (gear icon): target display, sensitivity, pointer acceleration, reverse scroll. Settings persist across launches, and the cursor automatically follows monitors being plugged in, unplugged, or changing resolution.

## 🔒 Device Permissions

Only one permission is needed. The cursor is drawn as an accessibility overlay, so **"Display over other apps" is not required**.

1. **Accessibility Access:** Open Settings > Accessibility > Downloaded apps (or Installed services). Find "Android Mouse" and toggle it **ON** (the app has a shortcut button for this).
2. **Ready to go!** Connect your device to an external monitor (Desktop Mode) and launch the app.

---
*Built for the future of mobile productivity.*
