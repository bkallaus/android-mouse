package com.example.androidmouse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextEditsTest {
    @Test fun insertAtEndWhenNoSelection() =
        assertEquals(TextEdits.Result("hello world", 11), TextEdits.insert("hello", -1, -1, " world"))

    @Test fun insertAtCaret() =
        assertEquals(TextEdits.Result("heXllo", 3), TextEdits.insert("hello", 2, 2, "X"))

    @Test fun insertReplacesReversedSelection() =
        assertEquals(TextEdits.Result("hXo", 2), TextEdits.insert("hello", 4, 1, "X"))

    @Test fun insertClampsOutOfRangeSelection() =
        assertEquals(TextEdits.Result("hiX", 3), TextEdits.insert("hi", 10, 10, "X"))

    @Test fun backspaceDeletesCharBeforeCaret() =
        assertEquals(TextEdits.Result("helo", 2), TextEdits.backspace("hello", 3, 3))

    @Test fun backspaceDeletesSelection() =
        assertEquals(TextEdits.Result("ho", 1), TextEdits.backspace("hello", 1, 4))

    @Test fun backspaceAtStartIsNoop() = assertNull(TextEdits.backspace("hello", 0, 0))

    @Test fun backspaceOnEmptyIsNoop() = assertNull(TextEdits.backspace("", -1, -1))

    @Test fun backspaceRemovesWholeEmoji() {
        val s = "a😀" // "a" followed by a surrogate-pair emoji
        assertEquals(TextEdits.Result("a", 1), TextEdits.backspace(s, s.length, s.length))
    }
}

class PointerAccelerationTest {
    @Test fun disabledIsUnity() =
        assertEquals(1f, PointerAcceleration.gain(100f, 0f, 1, enabled = false))

    @Test fun slowMovementIsUnity() =
        assertEquals(1f, PointerAcceleration.gain(2f, 0f, 16, enabled = true))

    @Test fun fastMovementIsAmplified() {
        val speed = 20f / 16f
        val expected = 1f + (speed - TrackpadConstants.ACCEL_SPEED_THRESHOLD) * TrackpadConstants.ACCEL_FACTOR
        assertEquals(expected, PointerAcceleration.gain(20f, 0f, 16, enabled = true), 1e-4f)
    }

    @Test fun gainIsCapped() =
        assertEquals(TrackpadConstants.ACCEL_MAX_GAIN, PointerAcceleration.gain(500f, 0f, 1, enabled = true))

    @Test fun zeroDtDoesNotDivideByZero() =
        assertEquals(TrackpadConstants.ACCEL_MAX_GAIN, PointerAcceleration.gain(50f, 0f, 0, enabled = true))
}
