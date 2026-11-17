package com.example.androidmouse

import kotlin.math.abs

/**
 * Pure touch state machine for the trackpad. It knows nothing about Android: the host
 * translates MotionEvents into the on* calls below and supplies the long-press timer and
 * haptic feedback as callbacks.
 *
 * Coordinates passed to the on* methods are in view pixels. When two or more pointers are down,
 * the host passes the centroid of the first two pointers, otherwise the single pointer's position.
 *
 * Invariants guaranteed on [emit]:
 *  - every DragMove lies between a DragStart and the matching DragStop,
 *  - two DragStarts are never emitted without a DragStop in between,
 *  - zero deltas are never emitted.
 */
class TrackpadGestureDetector(
    private val touchSlop: Float,
    private val backAction: Int,
    private val emit: (TrackpadEvent) -> Unit,
    private val scheduleLongPress: () -> Unit,
    private val cancelLongPress: () -> Unit,
    private val performHaptic: () -> Unit
) {
    /** Multiplier applied to every Move and DragMove delta. */
    var sensitivity: Float = 1.0f

    private enum class Phase {
        /** No finger down. */
        IDLE,

        /** One finger down, moving the cursor (or possibly about to tap / long-press). */
        SINGLE,

        /** Long-press drag in progress (DragStart(false) emitted). */
        DRAG,

        /** Two or more fingers down, centroid has not yet left the slop radius. */
        TWO_PENDING,

        /** Two-finger scroll in progress (DragStart(true) emitted). */
        SCROLL,

        /** A multi-finger gesture ended but a finger is still down: it moves the cursor, never taps. */
        RESIDUAL
    }

    private var phase = Phase.IDLE
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f

    /** SINGLE: finger has not left the slop radius since going down. */
    private var tapCandidate = false

    /** TWO_PENDING: this may still turn out to be a two-finger tap. */
    private var backCandidate = false

    fun onDown(x: Float, y: Float) {
        // Defensive: a new gesture must never start while a virtual finger is still held.
        endActiveDrag()
        cancelLongPress()
        phase = Phase.SINGLE
        startX = x
        startY = y
        lastX = x
        lastY = y
        tapCandidate = true
        backCandidate = false
        scheduleLongPress()
    }

    /**
     * An additional finger landed. [pointerCount] is the count including the new finger,
     * (x, y) the centroid of the first two pointers.
     */
    fun onPointerDown(pointerCount: Int, x: Float, y: Float) {
        if (phase == Phase.IDLE) return
        if (pointerCount < 2) {
            lastX = x
            lastY = y
            return
        }
        when (phase) {
            Phase.SCROLL -> {
                // Third finger: keep scrolling, just avoid a jump.
                lastX = x
                lastY = y
            }

            Phase.TWO_PENDING -> {
                startX = x
                startY = y
                lastX = x
                lastY = y
            }

            else -> {
                val wasTapCandidate = phase == Phase.SINGLE && tapCandidate
                endActiveDrag()
                cancelLongPress()
                phase = Phase.TWO_PENDING
                startX = x
                startY = y
                lastX = x
                lastY = y
                backCandidate = wasTapCandidate
                tapCandidate = false
                if (backCandidate) {
                    // Resting the fingers for a long press disqualifies the two-finger tap.
                    scheduleLongPress()
                }
            }
        }
    }

    /** Fingers moved. (x, y) is the centroid of the first two pointers, or the single pointer. */
    fun onMove(x: Float, y: Float) {
        when (phase) {
            Phase.SINGLE -> {
                emitMove(x - lastX, y - lastY)
                if (tapCandidate && (abs(x - startX) > touchSlop || abs(y - startY) > touchSlop)) {
                    tapCandidate = false
                    cancelLongPress()
                }
            }

            Phase.RESIDUAL -> emitMove(x - lastX, y - lastY)

            Phase.DRAG, Phase.SCROLL -> emitDragMove(x - lastX, y - lastY)

            Phase.TWO_PENDING -> {
                if (abs(x - startX) > touchSlop || abs(y - startY) > touchSlop) {
                    backCandidate = false
                    cancelLongPress()
                    phase = Phase.SCROLL
                    emit(TrackpadEvent.DragStart(isScroll = true))
                }
            }

            Phase.IDLE -> return
        }
        lastX = x
        lastY = y
    }

    /**
     * A finger lifted (not the last one). [remainingPointerCount] fingers are still down and
     * (x, y) is the centroid of the first two remaining pointers.
     */
    fun onPointerUp(remainingPointerCount: Int, x: Float, y: Float) {
        if (phase == Phase.IDLE) return
        if (remainingPointerCount <= 0) {
            onUp()
            return
        }
        lastX = x
        lastY = y
        if (remainingPointerCount >= 2) {
            if (phase == Phase.TWO_PENDING) {
                startX = x
                startY = y
            }
            return
        }
        when (phase) {
            Phase.SCROLL -> {
                emit(TrackpadEvent.DragStop)
                phase = Phase.RESIDUAL
            }

            Phase.TWO_PENDING -> {
                cancelLongPress()
                if (backCandidate) emit(TrackpadEvent.GlobalAction(backAction))
                backCandidate = false
                phase = Phase.RESIDUAL
            }

            Phase.DRAG -> {
                // Cannot normally happen (a second finger already ended the drag).
                emit(TrackpadEvent.DragStop)
                phase = Phase.RESIDUAL
            }

            Phase.SINGLE -> {
                tapCandidate = false
                phase = Phase.RESIDUAL
            }

            else -> Unit
        }
    }

    /** The last finger lifted. */
    fun onUp() {
        cancelLongPress()
        when (phase) {
            Phase.SINGLE -> if (tapCandidate) emit(TrackpadEvent.Tap)
            Phase.DRAG, Phase.SCROLL -> emit(TrackpadEvent.DragStop)
            Phase.TWO_PENDING -> if (backCandidate) emit(TrackpadEvent.GlobalAction(backAction))
            Phase.RESIDUAL, Phase.IDLE -> Unit
        }
        reset()
    }

    /** The gesture was cancelled by the system (or the host is pausing). Emits at most a DragStop. */
    fun onCancel() {
        cancelLongPress()
        endActiveDrag()
        reset()
    }

    /** The long-press timer scheduled via [scheduleLongPress] fired. */
    fun onLongPressTimeout() {
        when (phase) {
            Phase.SINGLE -> {
                if (!tapCandidate) return
                tapCandidate = false
                phase = Phase.DRAG
                emit(TrackpadEvent.DragStart(isScroll = false))
                performHaptic()
            }

            Phase.TWO_PENDING -> backCandidate = false
            else -> Unit
        }
    }

    private fun endActiveDrag() {
        if (phase == Phase.DRAG || phase == Phase.SCROLL) {
            emit(TrackpadEvent.DragStop)
        }
    }

    private fun reset() {
        phase = Phase.IDLE
        tapCandidate = false
        backCandidate = false
    }

    private fun emitMove(dx: Float, dy: Float) {
        val sx = dx * sensitivity
        val sy = dy * sensitivity
        if (sx != 0f || sy != 0f) emit(TrackpadEvent.Move(sx, sy))
    }

    private fun emitDragMove(dx: Float, dy: Float) {
        val sx = dx * sensitivity
        val sy = dy * sensitivity
        if (sx != 0f || sy != 0f) emit(TrackpadEvent.DragMove(sx, sy))
    }
}
