package com.example.androidmouse

import kotlin.math.hypot

/** Pure helpers kept free of Android types so they can be unit tested on the JVM. */
object TextEdits {

    data class Result(val text: String, val caret: Int)

    /** Clamp a (possibly reversed or missing) selection to [length]; caret defaults to the end. */
    fun normalizeSelection(selStart: Int, selEnd: Int, length: Int): Pair<Int, Int> {
        if (selStart < 0 || selEnd < 0) return length to length
        return minOf(selStart, selEnd).coerceIn(0, length) to maxOf(selStart, selEnd).coerceIn(0, length)
    }

    /** Insert [inserted] at the selection, replacing any selected text. */
    fun insert(current: String, selStart: Int, selEnd: Int, inserted: String): Result {
        val (start, end) = normalizeSelection(selStart, selEnd, current.length)
        return Result(current.substring(0, start) + inserted + current.substring(end), start + inserted.length)
    }

    /** Backspace: delete the selection, or the character before the caret. Null if nothing to delete. */
    fun backspace(current: String, selStart: Int, selEnd: Int): Result? {
        val (start, end) = normalizeSelection(selStart, selEnd, current.length)
        return when {
            start != end -> Result(current.removeRange(start, end), start)
            start > 0 -> {
                // Don't split a surrogate pair (e.g. an emoji) in half
                val from = if (start >= 2 &&
                    Character.isLowSurrogate(current[start - 1]) &&
                    Character.isHighSurrogate(current[start - 2])
                ) start - 2 else start - 1
                Result(current.removeRange(from, start), from)
            }
            else -> null
        }
    }
}

object PointerAcceleration {
    /**
     * Speed-based gain: slow finger movement stays 1:1 for precision, fast flicks are
     * amplified (capped) so the cursor can cross a large monitor in one swipe.
     */
    fun gain(dx: Float, dy: Float, dtMs: Long, enabled: Boolean): Float {
        if (!enabled) return 1f
        val speed = hypot(dx, dy) / dtMs.coerceAtLeast(1L)
        return (1f + (speed - TrackpadConstants.ACCEL_SPEED_THRESHOLD) * TrackpadConstants.ACCEL_FACTOR)
            .coerceIn(1f, TrackpadConstants.ACCEL_MAX_GAIN)
    }
}
