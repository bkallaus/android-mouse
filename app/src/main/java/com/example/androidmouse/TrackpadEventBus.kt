package com.example.androidmouse

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Event Bus using Kotlin Coroutines SharedFlow to communicate between the 
 * Trackpad Activity and Click Injector Service.
 */
object TrackpadEventBus {
    // Shared flow to send X and Y deltas from the Trackpad to the Overlay
    private val _moveEvents = MutableSharedFlow<Pair<Float, Float>>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val moveEvents = _moveEvents.asSharedFlow()

    // Shared flow to send tap commands from the Trackpad to the Click Injector
    private val _tapEvents = MutableSharedFlow<Unit>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val tapEvents = _tapEvents.asSharedFlow()

    // Shared flow to send global actions (Back, Home, Recents)
    private val _globalActions = MutableSharedFlow<Int>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val globalActions = _globalActions.asSharedFlow()

    // Shared flow to notify about display changes
    private val _displayChangeEvents = MutableSharedFlow<Int>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val displayChangeEvents = _displayChangeEvents.asSharedFlow()

    // Shared flow to send drag events
    private val _dragEvents = MutableSharedFlow<DragEvent>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val dragEvents = _dragEvents.asSharedFlow()

    // Shared flow to send text to be injected
    private val _textEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val textEvents = _textEvents.asSharedFlow()

    // Shared flow to send special key events (e.g. Enter, Backspace)
    private val _keyEvents = MutableSharedFlow<Int>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val keyEvents = _keyEvents.asSharedFlow()

    enum class DragAction { START, MOVE, STOP }
    data class DragEvent(
        val action: DragAction,
        val dx: Float = 0f,
        val dy: Float = 0f,
        val isScroll: Boolean = false
    )

    fun sendMoveEvent(dx: Float, dy: Float) {
        _moveEvents.tryEmit(Pair(dx, dy))
    }

    fun sendTapEvent() {
        _tapEvents.tryEmit(Unit)
    }

    fun sendGlobalAction(action: Int) {
        _globalActions.tryEmit(action)
    }

    fun sendDisplayChangeEvent(displayId: Int) {
        _displayChangeEvents.tryEmit(displayId)
    }

    fun sendDragEvent(action: DragAction, dx: Float = 0f, dy: Float = 0f, isScroll: Boolean = false) {
        _dragEvents.tryEmit(DragEvent(action, dx, dy, isScroll))
    }

    fun sendTextEvent(text: String) {
        _textEvents.tryEmit(text)
    }

    fun sendKeyEvent(keyCode: Int) {
        _keyEvents.tryEmit(keyCode)
    }
}
