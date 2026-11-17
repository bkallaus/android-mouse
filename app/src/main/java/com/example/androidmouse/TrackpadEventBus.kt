package com.example.androidmouse

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Everything the trackpad UI can ask the injector service to do.
 *
 * All events travel through one flow so the service handles them in the order
 * they were produced (a move followed by a tap must click at the moved-to position).
 */
sealed interface TrackpadEvent {
    /** Move the cursor by a delta, in target-display pixels. */
    data class Move(val dx: Float, val dy: Float) : TrackpadEvent

    /** Click at the current cursor position. */
    data object Tap : TrackpadEvent

    /**
     * Touch down at the cursor position and hold.
     * [isScroll] = true: the cursor stays put and only the virtual finger moves.
     * [isScroll] = false: the cursor moves together with the virtual finger (drag and drop).
     */
    data class DragStart(val isScroll: Boolean) : TrackpadEvent

    /** Move the held virtual finger by a delta. Only valid between DragStart and DragStop. */
    data class DragMove(val dx: Float, val dy: Float) : TrackpadEvent

    /** Lift the virtual finger. */
    data object DragStop : TrackpadEvent

    /** One of the AccessibilityService.GLOBAL_ACTION_* constants. */
    data class GlobalAction(val action: Int) : TrackpadEvent

    /** Move the cursor overlay and all injected input to this display. */
    data class SelectDisplay(val displayId: Int) : TrackpadEvent

    /** Insert text at the selection of the focused field on the target display. */
    data class Text(val text: String) : TrackpadEvent

    /** Delete the selection, or the character before the caret, in the focused field. */
    data object Backspace : TrackpadEvent

    /** Perform the IME enter action on the focused field. */
    data object Enter : TrackpadEvent
}

/**
 * In-process bridge between [TrackpadActivity] (producer) and
 * [ClickInjectorService] (consumer).
 */
object TrackpadEventBus {
    private val _events = MutableSharedFlow<TrackpadEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<TrackpadEvent> = _events.asSharedFlow()

    private val _activeDisplayId = MutableStateFlow<Int?>(null)

    /** The display the service is currently driving, or null while the service is not running. */
    val activeDisplayId: StateFlow<Int?> = _activeDisplayId.asStateFlow()

    fun send(event: TrackpadEvent) {
        _events.tryEmit(event)
    }

    /** Called by the service only. */
    fun setActiveDisplayId(displayId: Int?) {
        _activeDisplayId.value = displayId
    }
}
