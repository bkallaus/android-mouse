package com.example.androidmouse

/** One gesture segment the service must dispatch for a drag/scroll. */
sealed interface DragStep {
    /** Start a new stroke (touch down, willContinue = true) at the point. */
    data class Down(val x: Float, val y: Float) : DragStep

    /** Continue the current stroke from (fromX, fromY) to (toX, toY), finger stays down. */
    data class Move(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float) : DragStep

    /** Continue the current stroke stationary at the point and lift (willContinue = false). */
    data class Lift(val x: Float, val y: Float) : DragStep
}

/**
 * Android-free bookkeeping for a drag / scroll made of chained gesture segments.
 *
 * At most one step is in flight. The owner executes each returned [DragStep] and then reports the
 * outcome with [onCompleted] or [onFailed], which return the next step to execute (or null).
 * Every returned coordinate lies inside [0, width-1] x [0, height-1].
 */
class DragTracker(width: Int, height: Int) {

    private enum class Kind { NONE, DOWN, MOVE, LIFT_REANCHOR, LIFT_FINAL }

    private var maxX = (width - 1).coerceAtLeast(0).toFloat()
    private var maxY = (height - 1).coerceAtLeast(0).toFloat()

    var isActive = false
        private set
    private var isScroll = false
    private var inFlight = false
    private var fingerDown = false
    private var stopRequested = false
    private var kind = Kind.NONE
    private var failures = 0

    private var anchorX = 0f
    private var anchorY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var pendingX = 0f
    private var pendingY = 0f

    fun setBounds(width: Int, height: Int) {
        maxX = (width - 1).coerceAtLeast(0).toFloat()
        maxY = (height - 1).coerceAtLeast(0).toFloat()
    }

    /** Begins a drag, discarding any previous state. Returns the touch-down step. */
    fun start(scroll: Boolean, x: Float, y: Float): DragStep {
        finish()
        failures = 0
        isActive = true
        isScroll = scroll
        anchorX = x.coerceIn(0f, maxX)
        anchorY = y.coerceIn(0f, maxY)
        pendingX = anchorX
        pendingY = anchorY
        return emitDown(anchorX, anchorY)
    }

    /** Accumulates movement. Returns a step only if nothing is in flight. */
    fun move(dx: Float, dy: Float): DragStep? {
        if (!isActive || stopRequested) return null
        pendingX += dx
        pendingY += dy
        if (!isScroll) {
            pendingX = pendingX.coerceIn(0f, maxX)
            pendingY = pendingY.coerceIn(0f, maxY)
        }
        return if (inFlight) null else next()
    }

    /** Requests lifting the finger once pending movement has been flushed. */
    fun stop(): DragStep? {
        if (!isActive || stopRequested) return null
        stopRequested = true
        pendingX = pendingX.coerceIn(0f, maxX)
        pendingY = pendingY.coerceIn(0f, maxY)
        return if (inFlight) null else next()
    }

    /** The in-flight step finished successfully. */
    fun onCompleted(): DragStep? {
        if (!isActive || !inFlight) return null
        inFlight = false
        failures = 0
        return afterStep()
    }

    /** The in-flight step was cancelled, rejected or threw. */
    fun onFailed(): DragStep? {
        if (!isActive || !inFlight) return null
        inFlight = false
        return when (kind) {
            Kind.LIFT_FINAL, Kind.LIFT_REANCHOR -> afterStep() // the finger is up either way
            else -> {
                if (stopRequested) {
                    finish()
                    null
                } else if (++failures > MAX_CONSECUTIVE_FAILURES) {
                    finish()
                    null
                } else {
                    emitDown(lastX, lastY)
                }
            }
        }
    }

    /**
     * Drops all state immediately. Returns a lift to dispatch if the finger is believed to be
     * down and no segment is in flight (an in-flight segment cannot be continued safely).
     */
    fun abort(): DragStep.Lift? {
        val lift = if (isActive && !inFlight && fingerDown) DragStep.Lift(lastX, lastY) else null
        finish()
        return lift
    }

    private fun afterStep(): DragStep? = when (kind) {
        Kind.LIFT_FINAL -> {
            finish()
            null
        }
        Kind.LIFT_REANCHOR -> {
            if (stopRequested) {
                finish()
                null
            } else {
                emitDown(anchorX, anchorY)
            }
        }
        else -> next()
    }

    private fun next(): DragStep? {
        if (!isActive || inFlight) return null
        val cx = pendingX.coerceIn(0f, maxX)
        val cy = pendingY.coerceIn(0f, maxY)
        if (cx != lastX || cy != lastY) {
            val step = DragStep.Move(lastX, lastY, cx, cy)
            lastX = cx
            lastY = cy
            kind = Kind.MOVE
            inFlight = true
            return step
        }
        val overflowX = pendingX - cx
        val overflowY = pendingY - cy
        if ((overflowX != 0f || overflowY != 0f) && !stopRequested) {
            // Scroll hit the display edge: lift here and re-anchor, carrying the remainder over.
            pendingX = (anchorX + overflowX).coerceIn(0f, maxX)
            pendingY = (anchorY + overflowY).coerceIn(0f, maxY)
            return emitLift(Kind.LIFT_REANCHOR)
        }
        if (stopRequested) return emitLift(Kind.LIFT_FINAL)
        return null
    }

    private fun emitLift(k: Kind): DragStep {
        kind = k
        inFlight = true
        fingerDown = false
        return DragStep.Lift(lastX, lastY)
    }

    private fun emitDown(x: Float, y: Float): DragStep {
        lastX = x
        lastY = y
        kind = Kind.DOWN
        inFlight = true
        fingerDown = true
        return DragStep.Down(x, y)
    }

    private fun finish() {
        isActive = false
        inFlight = false
        fingerDown = false
        stopRequested = false
        kind = Kind.NONE
    }

    companion object {
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
