package com.example.androidmouse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DragTrackerTest {

    private fun tracker() = DragTracker(1000, 500)

    @Test
    fun dragFlowsDownMoveLift() {
        val t = tracker()
        assertEquals(DragStep.Down(100f, 100f), t.start(false, 100f, 100f))
        // Move while Down in flight is accumulated, not dispatched.
        assertNull(t.move(10f, 0f))
        assertNull(t.move(5f, 5f))
        assertNull(t.stop())
        assertEquals(DragStep.Move(100f, 100f, 115f, 105f), t.onCompleted())
        // Move completes, stop pending -> lift at final position.
        assertEquals(DragStep.Lift(115f, 105f), t.onCompleted())
        assertNull(t.onCompleted())
        assertFalse(t.isActive)
    }

    @Test
    fun idleThenMoveDispatchesImmediately() {
        val t = tracker()
        t.start(false, 10f, 10f)
        assertNull(t.onCompleted())
        assertEquals(DragStep.Move(10f, 10f, 20f, 10f), t.move(10f, 0f))
        assertNull(t.move(1f, 0f))
        assertEquals(DragStep.Move(20f, 10f, 21f, 10f), t.onCompleted())
    }

    @Test
    fun stopWhenIdleLiftsAtOnce() {
        val t = tracker()
        t.start(true, 10f, 10f)
        t.onCompleted()
        assertEquals(DragStep.Lift(10f, 10f), t.stop())
        assertNull(t.onCompleted())
        assertFalse(t.isActive)
    }

    @Test
    fun nonScrollDragClampsToDisplay() {
        val t = tracker()
        t.start(false, 990f, 10f)
        t.onCompleted()
        assertEquals(DragStep.Move(990f, 10f, 999f, 0f), t.move(500f, -500f))
    }

    @Test
    fun scrollAtEdgeLiftsAndReanchors() {
        val t = tracker()
        t.start(true, 500f, 250f)
        t.onCompleted()
        // Scroll 800 down: only 249 fit.
        assertEquals(DragStep.Move(500f, 250f, 500f, 499f), t.move(0f, 800f))
        // Reaching the edge with overflow -> lift, then re-anchor.
        assertEquals(DragStep.Lift(500f, 499f), t.onCompleted())
        assertEquals(DragStep.Down(500f, 250f), t.onCompleted())
        // Remaining overflow (800 - 249 = 551) is carried: anchor + 551 is out of range -> clamped.
        assertEquals(DragStep.Move(500f, 250f, 500f, 499f), t.onCompleted())
    }

    @Test
    fun scrollAtEdgeWithAnchorAtEdgeDoesNotLoop() {
        val t = tracker()
        t.start(true, 500f, 499f)
        t.onCompleted()
        // Already at the edge, scroll further: lift + re-anchor, then idle (no infinite loop).
        assertEquals(DragStep.Lift(500f, 499f), t.move(0f, 50f))
        assertEquals(DragStep.Down(500f, 499f), t.onCompleted())
        assertNull(t.onCompleted())
    }

    @Test
    fun stopDuringReanchorLiftEnds() {
        val t = tracker()
        t.start(true, 500f, 250f)
        t.onCompleted()
        t.move(0f, 800f)
        assertEquals(DragStep.Lift(500f, 499f), t.onCompleted())
        assertNull(t.stop())
        assertNull(t.onCompleted())
        assertFalse(t.isActive)
    }

    @Test
    fun cancelledWhileDraggingRestartsAtFingerPosition() {
        val t = tracker()
        t.start(false, 100f, 100f)
        t.onCompleted()
        assertEquals(DragStep.Move(100f, 100f, 150f, 100f), t.move(50f, 0f))
        assertEquals(DragStep.Down(150f, 100f), t.onFailed())
        assertNull(t.onCompleted())
        assertTrue(t.isActive)
    }

    @Test
    fun cancelAfterStopJustEnds() {
        val t = tracker()
        t.start(false, 100f, 100f)
        t.onCompleted()
        t.move(50f, 0f)
        assertNull(t.stop())
        assertNull(t.onFailed())
        assertFalse(t.isActive)
    }

    @Test
    fun givesUpAfterRepeatedFailures() {
        val t = tracker()
        t.start(false, 100f, 100f)
        repeat(DragTracker.MAX_CONSECUTIVE_FAILURES) {
            assertTrue(t.onFailed() is DragStep.Down)
        }
        assertNull(t.onFailed())
        assertFalse(t.isActive)
        assertNull(t.move(5f, 5f))
        // A new drag works again.
        assertTrue(t.start(false, 1f, 1f) is DragStep.Down)
    }

    @Test
    fun abortLiftsOnlyWhenIdleAndFingerDown() {
        val t = tracker()
        t.start(false, 100f, 100f)
        assertNull(t.abort()) // Down in flight
        assertFalse(t.isActive)

        t.start(false, 100f, 100f)
        t.onCompleted()
        assertEquals(DragStep.Lift(100f, 100f), t.abort())
        assertNull(t.onCompleted())
    }
}
