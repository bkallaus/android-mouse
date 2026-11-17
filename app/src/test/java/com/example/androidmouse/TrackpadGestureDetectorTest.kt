package com.example.androidmouse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrackpadGestureDetectorTest {

    private val slop = 10f
    private val back = 4

    private val events = mutableListOf<TrackpadEvent>()
    private var scheduled = 0
    private var cancelled = 0
    private var haptics = 0
    private lateinit var d: TrackpadGestureDetector

    @Before
    fun setUp() {
        events.clear()
        scheduled = 0
        cancelled = 0
        haptics = 0
        d = TrackpadGestureDetector(
            touchSlop = slop,
            backAction = back,
            emit = { events.add(it) },
            scheduleLongPress = { scheduled++ },
            cancelLongPress = { cancelled++ },
            performHaptic = { haptics++ }
        )
    }

    private fun assertDragPairing() {
        var active = false
        for (e in events) {
            when (e) {
                is TrackpadEvent.DragStart -> {
                    assertFalse("DragStart while already dragging: $events", active)
                    active = true
                }

                is TrackpadEvent.DragMove -> assertTrue("DragMove outside drag: $events", active)
                TrackpadEvent.DragStop -> {
                    assertTrue("DragStop without DragStart: $events", active)
                    active = false
                }

                else -> Unit
            }
        }
        assertFalse("Drag left open: $events", active)
    }

    @Test
    fun oneFingerMoveEmitsMoves() {
        d.onDown(100f, 100f)
        d.onMove(115f, 105f)
        d.onMove(115f, 101f)
        d.onUp()
        assertEquals(
            listOf(TrackpadEvent.Move(15f, 5f), TrackpadEvent.Move(0f, -4f)),
            events
        )
    }

    @Test
    fun zeroDeltasAreSkipped() {
        d.onDown(100f, 100f)
        d.onMove(100f, 100f)
        d.onMove(100f, 100f)
        d.onUp()
        assertEquals(listOf<TrackpadEvent>(TrackpadEvent.Tap), events)
    }

    @Test
    fun tapWithinSlopEmitsTap() {
        d.onDown(100f, 100f)
        assertEquals(1, scheduled)
        d.onMove(104f, 103f)
        d.onUp()
        assertEquals(listOf(TrackpadEvent.Move(4f, 3f), TrackpadEvent.Tap), events)
    }

    @Test
    fun movingBeyondSlopIsNotATapAndCancelsTimer() {
        d.onDown(100f, 100f)
        val before = cancelled
        d.onMove(120f, 100f)
        assertTrue(cancelled > before)
        d.onUp()
        assertFalse(events.contains(TrackpadEvent.Tap))
        // A late timer firing must not start a drag either.
        d.onLongPressTimeout()
        assertEquals(listOf<TrackpadEvent>(TrackpadEvent.Move(20f, 0f)), events)
    }

    @Test
    fun longPressStartsDragMovesAndStops() {
        d.onDown(100f, 100f)
        d.onLongPressTimeout()
        assertEquals(1, haptics)
        d.onMove(110f, 95f)
        d.onUp()
        assertEquals(
            listOf(
                TrackpadEvent.DragStart(false),
                TrackpadEvent.DragMove(10f, -5f),
                TrackpadEvent.DragStop
            ),
            events
        )
        assertDragPairing()
    }

    @Test
    fun longPressWithoutMovementThenLiftDoesNotTap() {
        d.onDown(100f, 100f)
        d.onLongPressTimeout()
        d.onUp()
        assertEquals(listOf(TrackpadEvent.DragStart(false), TrackpadEvent.DragStop), events)
    }

    @Test
    fun twoFingerTapEmitsBackAndNoTap() {
        d.onDown(100f, 100f)
        d.onPointerDown(2, 110f, 100f)
        d.onPointerUp(1, 100f, 100f)
        d.onUp()
        assertEquals(listOf<TrackpadEvent>(TrackpadEvent.GlobalAction(back)), events)
    }

    @Test
    fun twoFingerRestDoesNotStartDragOrScroll() {
        d.onDown(100f, 100f)
        d.onPointerDown(2, 110f, 100f)
        assertTrue(events.isEmpty())
        // Long-press timer firing while two fingers rest must not start a drag.
        d.onLongPressTimeout()
        assertTrue(events.isEmpty())
    }

    @Test
    fun twoFingerRestPastLongPressIsNotBack() {
        d.onDown(100f, 100f)
        d.onPointerDown(2, 110f, 100f)
        d.onLongPressTimeout()
        d.onPointerUp(1, 100f, 100f)
        d.onUp()
        assertTrue(events.isEmpty())
    }

    @Test
    fun twoFingerJitterWithinSlopStillBack() {
        d.onDown(100f, 100f)
        d.onPointerDown(2, 110f, 100f)
        d.onMove(113f, 104f)
        d.onMove(108f, 98f)
        d.onPointerUp(1, 100f, 100f)
        d.onUp()
        assertEquals(listOf<TrackpadEvent>(TrackpadEvent.GlobalAction(back)), events)
    }

    @Test
    fun scrollStartsOnlyAfterSlopExceeded() {
        d.onDown(100f, 100f)
        d.onPointerDown(2, 100f, 100f)
        d.onMove(100f, 105f)
        assertTrue(events.isEmpty())
        d.onMove(100f, 111f) // 11 > slop: DragStart, baseline here
        assertEquals(listOf<TrackpadEvent>(TrackpadEvent.DragStart(true)), events)
        d.onMove(100f, 121f)
        d.onPointerUp(1, 100f, 121f)
        assertEquals(
            listOf(
                TrackpadEvent.DragStart(true),
                TrackpadEvent.DragMove(0f, 10f),
                TrackpadEvent.DragStop
            ),
            events
        )
        assertEquals(0, haptics)
        assertDragPairing()
    }

    @Test
    fun scrollThenBothFingersUpStopsOnce() {
        d.onDown(100f, 100f)
        d.onPointerDown(2, 100f, 100f)
        d.onMove(100f, 130f)
        d.onMove(100f, 140f)
        d.onPointerUp(1, 100f, 140f)
        d.onUp()
        assertEquals(1, events.count { it == TrackpadEvent.DragStop })
        assertFalse(events.any { it is TrackpadEvent.GlobalAction })
        assertDragPairing()
    }

    @Test
    fun secondFingerDuringDragStopsDragThenWaitsForSlop() {
        d.onDown(100f, 100f)
        d.onLongPressTimeout()
        d.onMove(105f, 100f)
        d.onPointerDown(2, 110f, 100f)
        assertEquals(
            listOf(
                TrackpadEvent.DragStart(false),
                TrackpadEvent.DragMove(5f, 0f),
                TrackpadEvent.DragStop
            ),
            events
        )
        d.onMove(110f, 105f) // within slop, nothing
        assertEquals(3, events.size)
        d.onPointerUp(1, 100f, 100f)
        d.onUp()
        // Dragging then adding a finger is not a two-finger tap.
        assertEquals(3, events.size)
        assertDragPairing()
    }

    @Test
    fun threeFingerLiftKeepsScrolling() {
        d.onDown(100f, 100f)
        d.onPointerDown(2, 100f, 100f)
        d.onMove(100f, 120f) // DragStart
        d.onPointerDown(3, 100f, 150f) // third finger: re-baseline, no jump
        d.onMove(100f, 160f)
        d.onPointerUp(2, 100f, 200f) // 3 -> 2, re-baseline
        d.onMove(100f, 205f)
        assertEquals(
            listOf(
                TrackpadEvent.DragStart(true),
                TrackpadEvent.DragMove(0f, 10f),
                TrackpadEvent.DragMove(0f, 5f)
            ),
            events
        )
        d.onPointerUp(1, 100f, 205f)
        assertEquals(TrackpadEvent.DragStop, events.last())
        d.onUp()
        assertEquals(1, events.count { it == TrackpadEvent.DragStop })
        assertDragPairing()
    }

    @Test
    fun scrollThenSingleFingerMovesCursorWithoutTap() {
        d.onDown(100f, 100f)
        d.onPointerDown(2, 100f, 100f)
        d.onMove(100f, 130f)
        d.onPointerUp(1, 50f, 60f) // remaining finger elsewhere: no jump
        d.onMove(55f, 60f)
        d.onUp()
        assertEquals(
            listOf(
                TrackpadEvent.DragStart(true),
                TrackpadEvent.DragStop,
                TrackpadEvent.Move(5f, 0f)
            ),
            events
        )
        assertFalse(events.contains(TrackpadEvent.Tap))
    }

    @Test
    fun scrollThenSingleFingerLiftWithoutMovingIsNotTap() {
        d.onDown(100f, 100f)
        d.onPointerDown(2, 100f, 100f)
        d.onMove(100f, 130f)
        d.onPointerUp(1, 100f, 130f)
        d.onUp()
        assertEquals(listOf(TrackpadEvent.DragStart(true), TrackpadEvent.DragStop), events)
    }

    @Test
    fun cancelDuringDragStopsDrag() {
        d.onDown(100f, 100f)
        d.onLongPressTimeout()
        val before = cancelled
        d.onCancel()
        assertTrue(cancelled > before)
        assertEquals(listOf(TrackpadEvent.DragStart(false), TrackpadEvent.DragStop), events)
    }

    @Test
    fun cancelDuringScrollStopsScroll() {
        d.onDown(100f, 100f)
        d.onPointerDown(2, 100f, 100f)
        d.onMove(100f, 130f)
        d.onCancel()
        assertEquals(listOf(TrackpadEvent.DragStart(true), TrackpadEvent.DragStop), events)
    }

    @Test
    fun cancelOtherwiseEmitsNothing() {
        d.onDown(100f, 100f)
        d.onCancel()
        d.onDown(100f, 100f)
        d.onPointerDown(2, 100f, 100f)
        d.onCancel()
        assertTrue(events.isEmpty())
    }

    @Test
    fun cancelThenStaleTimerDoesNothing() {
        d.onDown(100f, 100f)
        d.onCancel()
        d.onLongPressTimeout()
        assertTrue(events.isEmpty())
    }

    @Test
    fun sensitivityScalesMoveAndDragMove() {
        d.sensitivity = 2f
        d.onDown(0f, 0f)
        d.onMove(3f, -1f)
        d.onLongPressTimeout() // tap candidate still within slop
        d.onMove(5f, -1f)
        d.onUp()
        assertEquals(
            listOf(
                TrackpadEvent.Move(6f, -2f),
                TrackpadEvent.DragStart(false),
                TrackpadEvent.DragMove(4f, 0f),
                TrackpadEvent.DragStop
            ),
            events
        )
    }

    @Test
    fun sensitivityScalesScroll() {
        d.sensitivity = 0.5f
        d.onDown(0f, 0f)
        d.onPointerDown(2, 0f, 0f)
        d.onMove(0f, 20f)
        d.onMove(0f, 40f)
        d.onUp()
        assertEquals(TrackpadEvent.DragMove(0f, 10f), events[1])
    }

    @Test
    fun repeatedGesturesKeepPairingInvariant() {
        repeat(3) {
            d.onDown(0f, 0f)
            d.onLongPressTimeout()
            d.onMove(20f, 20f)
            d.onPointerDown(2, 20f, 20f)
            d.onMove(20f, 60f)
            d.onPointerDown(3, 20f, 60f)
            d.onPointerUp(2, 20f, 60f)
            d.onMove(20f, 80f)
            d.onPointerUp(1, 20f, 80f)
            d.onMove(30f, 80f)
            d.onUp()
        }
        assertDragPairing()
    }

    @Test
    fun newDownWhileDragActiveClosesDragFirst() {
        d.onDown(0f, 0f)
        d.onLongPressTimeout()
        d.onDown(5f, 5f) // missed UP
        assertEquals(listOf(TrackpadEvent.DragStart(false), TrackpadEvent.DragStop), events)
        assertDragPairing()
    }
}
