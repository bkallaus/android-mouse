# 🖱️ Android Mouse

[![Android API 30+](https://img.shields.io/badge/API-30%2B-brightgreen.svg?style=flat)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-blue.svg?logo=kotlin)](#)

**Turn your Android device into a hardware-free trackpad for external displays.**

Have you ever plugged your phone into an external monitor for Desktop Mode, only to realize you left your Bluetooth mouse at home? **Android Mouse** turns your phone's screen into a trackpad that controls an on-screen cursor on the external display.

No root required. Native Android APIs. Pure Kotlin.

## ✨ Features

- **Responsive Movement:** Finger movement is turned into cursor deltas and delivered through a single, ordered `SharedFlow` event stream.
- **Native Implementation:** Uses the built-in Android Accessibility APIs, with no root and no third-party input injectors.
- **External Display Support:** Follows the external display automatically when one is connected, remembers the last display you chose, and lets you change the target in Settings.
- **System-Wide Input:** Injects taps, drags and scrolls on the target display, and types into the focused field.
- **On-Device Only:** The app requests no network permission; nothing leaves your phone.

## 👆 Gestures and Controls

| Gesture | Action |
| --- | --- |
| One finger | Move the cursor |
| Tap | Click |
| Touch and hold, then move | Drag |
| Two fingers | Scroll |
| Two-finger tap | Back |

On-screen buttons:

- **Back**, **Home**, **Recents:** system navigation actions.
- **Keyboard:** opens the phone keyboard and types into the focused field on the external display, including backspace and enter.
- **Settings:** choose the target display and adjust cursor sensitivity.

## 🏗️ How it Works

1. **Trackpad Activity:** The touch surface and buttons. It feeds raw touches to `TrackpadGestureDetector`, an Android-free (and unit-tested) class that turns them into moves, taps, drags, scrolls and back taps.
2. **Event flow:** The activity publishes `TrackpadEvent`s to `TrackpadEventBus`, a single ordered flow that preserves the sequence of events.
3. **Click Injector:** `ClickInjectorService` is an `AccessibilityService` that collects the events. It owns the cursor overlay (a `TYPE_ACCESSIBILITY_OVERLAY` window on the target display), tracks displays as they come and go, and injects the gestures.

## 🔒 Setup

There is one setup step: enable the accessibility service.

1. Launch **Android Mouse**. If the service is off, the app shows a button that opens Accessibility settings.
2. Find **Android Mouse** (under Downloaded apps / Installed services) and turn it **ON**.
3. Connect an external monitor (Desktop Mode) and use the trackpad.

Why an accessibility service? Android only lets accessibility services inject taps and gestures into other apps, draw an overlay such as the cursor on another display, and enter text into the focused field. The app uses it for nothing else, and it has no network permission, so everything stays on the device.

## 🔨 Building

Requires JDK 17+ (Android Studio's bundled JDK works) and runs on Android 11 (API 30) or newer.

```
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

---
*Built for the future of mobile productivity.*
