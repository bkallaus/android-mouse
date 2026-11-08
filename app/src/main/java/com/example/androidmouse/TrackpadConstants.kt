package com.example.androidmouse

object TrackpadConstants {
    const val PREFS_NAME = "trackpad_prefs"
    const val KEY_LAST_DISPLAY_ID = "last_display_id"
    const val KEY_SENSITIVITY = "sensitivity"
    const val KEY_ACCELERATION = "acceleration"
    const val KEY_REVERSE_SCROLL = "reverse_scroll"
    
    const val DEFAULT_SENSITIVITY = 1.0f

    // A two-finger touch shorter than this (with no real movement) is a right click
    const val TWO_FINGER_TAP_TIMEOUT_MS = 250L

    // Pointer acceleration curve: gain grows with finger speed (px/ms) and is capped
    const val ACCEL_SPEED_THRESHOLD = 0.4f
    const val ACCEL_FACTOR = 1.5f
    const val ACCEL_MAX_GAIN = 3.0f
}
