# Android Mouse

This project provides the core boilerplate to turn an Android device into a hardware-free trackpad that controls a secondary external display using Android's native Accessibility and Overlay capabilities.

## Architecture Structure
The application communicates in real-time through a highly performant `TrackpadEventBus` (Kotlin Coroutines `SharedFlow`) and comprises three primary components:
1. **TrackpadActivity**: The blank full-screen activity acting as the touch surface. Calculates X/Y deltas and detects quick taps.
2. **CursorOverlayService**: Detects the secondary display using `DisplayManager`, scopes a new `WindowContext`, and uses `SYSTEM_ALERT_WINDOW` logic to draw a floating cursor. Tracks real-time absolute screen coordinates.
3. **ClickInjectorService**: An `AccessibilityService` that observes tap commands from the EventBus, acquires absolute screen coordinates from the OverlayService, and uses `dispatchGesture()` specifically routed to the external display.

## Build Instructions 

The provided files are source-level boilerplate files. To build and compile this properly into a functioning Android Application:

### 1. Project Setup
1. Open **Android Studio**.
2. Select **File > New > New Project**, choose **Empty Activity**, and click Next.
3. Set Name to **Android Mouse** and Language to **Kotlin**.
4. Set Minimum SDK to **API 30** (Android 11.0) because routing a `GestureDescription` to an external display requires API 30+.
5. Click **Finish**.

### 2. Copy Source Files
Move or copy the generated files from this `android-mouse` directory directly into your new Android Studio project. 
* *Copy the `.kt` files into your `app/src/main/java/com/example/androidmouse/` directory.*
* *Replace your existing `AndroidManifest.xml` with the provided one.*
* *Place `accessibility_service_config.xml` into the `app/src/main/res/xml/` directory.*

### 3. Add Resources & Dependencies
Open your app-level `build.gradle.kts` file and ensure Kotlin Coroutines are included in your dependencies:
```kotlin
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    // ...other standard dependencies
}
```

Open `app/src/main/res/values/strings.xml` and add the string referenced by the accessibility config:
```xml
<resources>
    <string name="app_name">Android Mouse</string>
    <string name="accessibility_description">Android Mouse Click Injector</string>
</resources>
```

Sync your Gradle project.

### 4. Build and Run
1. Connect your Android device via USB/Wi-Fi debugging.
2. Click **Run** (`Shift + F10`) in Android Studio.

## Device Permissions Setup
Because this application simulates mouse input and overlays UI on an external screen, Android restricts it. **You must manually grant these permissions before the application will work:**
1. **Overlay Permission**: Open your device Settings app > Apps > Special app access > **Display over other apps**. Find "Android Mouse" and toggle it **ON**.
2. **Accessibility Permission**: Open your device Settings app > Accessibility > Downloaded apps (or Installed services). Find "Android Mouse" and toggle **Use Android Mouse** to **ON**.
3. Assure your device is plugged into an external monitor (Desktop Mode). Launch the main Trackpad Activity on the primary device screen.
