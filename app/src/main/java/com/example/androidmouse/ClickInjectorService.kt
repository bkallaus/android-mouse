package com.example.androidmouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import kotlinx.coroutines.*

class ClickInjectorService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var windowManager: WindowManager? = null
    private var cursorView: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // Track the absolute position of the cursor
    private var currentX = 0f
    private var currentY = 0f
    private var lastSentX = -1
    private var lastSentY = -1
    private var cursorSize = 0
    private var externalDisplayId: Int = Display.DEFAULT_DISPLAY
    private var displayWidth = 1920
    private var displayHeight = 1080

    private var currentDragStroke: GestureDescription.StrokeDescription? = null
    private var gestureX = 0f
    private var gestureY = 0f

    private var displayManager: DisplayManager? = null

    // Keeps the cursor valid when monitors are plugged/unplugged, rotated or change resolution
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            // Auto-attach to a newly connected monitor if we are currently on the phone screen
            if (externalDisplayId == Display.DEFAULT_DISPLAY && displayId != Display.DEFAULT_DISPLAY) {
                setupOverlay(displayId)
            }
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == externalDisplayId) {
                currentDragStroke = null
                setupOverlay()
            }
        }

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != externalDisplayId) return
            val display = displayManager?.getDisplay(displayId) ?: return
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            if (size.x != displayWidth || size.y != displayHeight) {
                displayWidth = size.x
                displayHeight = size.y
                currentX = currentX.coerceIn(0f, displayWidth - 1f)
                currentY = currentY.coerceIn(0f, displayHeight - 1f)
                updateCursorPosition(force = true)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service Connected")
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager = dm
        dm.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))

        // Restore the display the user picked last time, if it is still connected
        val savedDisplayId = getSharedPreferences(TrackpadConstants.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(TrackpadConstants.KEY_LAST_DISPLAY_ID, -1)
        setupOverlay(if (dm.getDisplay(savedDisplayId) != null) savedDisplayId else null)
        observeEvents()
    }

    private fun setupOverlay(displayId: Int? = null) {
        try {
            // Remove previous view using the manager it was added to
            cursorView?.let { view ->
                try {
                    windowManager?.removeView(view)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing old cursor view", e)
                }
            }
            cursorView = null

            val displays = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).displays

            val targetDisplay = if (displayId != null) {
                displays.firstOrNull { it.displayId == displayId }
            } else {
                displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
                    ?: displays.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY }
            }

            if (targetDisplay == null) return

            externalDisplayId = targetDisplay.displayId
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            targetDisplay.getRealSize(size)
            displayWidth = size.x
            displayHeight = size.y

            currentX = displayWidth / 2f
            currentY = displayHeight / 2f

            val displayContext = createDisplayContext(targetDisplay)
            windowManager = displayContext.getSystemService(WINDOW_SERVICE) as WindowManager
            cursorSize = displayContext.resources.getDimensionPixelSize(R.dimen.cursor_size)

            cursorView = ImageView(displayContext).apply {
                setImageResource(R.drawable.cursor)
                alpha = 1.0f
            }

            // TYPE_ACCESSIBILITY_OVERLAY is granted by the accessibility service itself,
            // so no "Display over other apps" permission is needed.
            layoutParams = WindowManager.LayoutParams(
                cursorSize,
                cursorSize,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = currentX.toInt()
                y = currentY.toInt()
            }

            windowManager?.addView(cursorView, layoutParams)
            lastSentX = currentX.toInt()
            lastSentY = currentY.toInt()
        } catch (e: Exception) {
            Log.e(TAG, "Error in setupOverlay", e)
        }
    }

    private fun observeEvents() {
        serviceScope.launch {
            TrackpadEventBus.moveEvents.collect { (dx, dy) ->
                currentX = (currentX + dx).coerceIn(0f, displayWidth.toFloat() - 1f)
                currentY = (currentY + dy).coerceIn(0f, displayHeight.toFloat() - 1f)
                updateCursorPosition()
            }
        }
        serviceScope.launch {
            TrackpadEventBus.tapEvents.collect {
                performClick(currentX, currentY, externalDisplayId)
            }
        }
        serviceScope.launch {
            TrackpadEventBus.rightClickEvents.collect {
                performLongPress(currentX, currentY, externalDisplayId)
            }
        }
        serviceScope.launch {
            TrackpadEventBus.dragEvents.collect { event ->
                handleDragEvent(event)
            }
        }
        serviceScope.launch {
            TrackpadEventBus.displayChangeEvents.collect { displayId ->
                if (displayId != externalDisplayId) {
                    setupOverlay(displayId)
                }
            }
        }
        serviceScope.launch {
            TrackpadEventBus.globalActions.collect { action ->
                // Global actions apply to the focused display. Only "click to focus" when the
                // external display is not already focused, so Back/Home don't also press
                // whatever happens to be under the cursor.
                if (!isDisplayFocused(externalDisplayId)) {
                    performClick(currentX, currentY, externalDisplayId)
                    delay(200)
                }
                performGlobalAction(action)
            }
        }
        serviceScope.launch {
            TrackpadEventBus.textEvents.collect { text ->
                injectText(text)
            }
        }
        serviceScope.launch {
            TrackpadEventBus.keyEvents.collect { keyCode ->
                handleKeyEvent(keyCode)
            }
        }
    }

    private fun isDisplayFocused(displayId: Int): Boolean {
        val windows = getWindowsOnAllDisplays()[displayId] ?: return false
        return windows.any { it.isFocused }
    }

    private fun getFocusedNode(): AccessibilityNodeInfo? {
        val windows = getWindowsOnAllDisplays()[externalDisplayId] ?: return null
        for (window in windows) {
            val root = window.root ?: continue
            val found = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            @Suppress("DEPRECATION")
            root.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun handleKeyEvent(keyCode: Int) {
        val focusedNode = getFocusedNode() ?: return
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER ->
                focusedNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            KeyEvent.KEYCODE_DEL -> deleteBeforeCursor(focusedNode)
        }
        @Suppress("DEPRECATION")
        focusedNode.recycle()
    }

    private fun injectText(text: String) {
        val focusedNode = getFocusedNode()
        if (focusedNode == null) {
            Log.d(TAG, "No focused node found to inject text")
            return
        }
        // ACTION_SET_TEXT replaces the whole field, so splice the new text in at the caret
        // (replacing any selection) instead of wiping what is already there.
        val edit = TextEdits.insert(
            editableText(focusedNode), focusedNode.textSelectionStart, focusedNode.textSelectionEnd, text
        )
        applyEdit(focusedNode, edit)
        @Suppress("DEPRECATION")
        focusedNode.recycle()
    }

    private fun deleteBeforeCursor(node: AccessibilityNodeInfo) {
        TextEdits.backspace(editableText(node), node.textSelectionStart, node.textSelectionEnd)
            ?.let { applyEdit(node, it) }
    }

    /** Current editable text of [node], ignoring placeholder hint text. */
    private fun editableText(node: AccessibilityNodeInfo): String =
        if (node.isShowingHintText) "" else node.text?.toString() ?: ""

    private fun applyEdit(node: AccessibilityNodeInfo, edit: TextEdits.Result) {
        val (text, caret) = edit
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
        })
    }

    private fun updateCursorPosition(force: Boolean = false) {
        val nextX = currentX.toInt()
        val nextY = currentY.toInt()

        if (!force && nextX == lastSentX && nextY == lastSentY) return

        layoutParams?.let { lp ->
            lp.x = nextX
            lp.y = nextY
            cursorView?.let { view ->
                try {
                    windowManager?.updateViewLayout(view, lp)
                    lastSentX = nextX
                    lastSentY = nextY
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating cursor position", e)
                }
            }
        }
    }

    private fun dispatchOnDisplay(stroke: GestureDescription.StrokeDescription) {
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .setDisplayId(externalDisplayId)
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun handleDragEvent(event: TrackpadEventBus.DragEvent) {
        try {
            when (event.action) {
                TrackpadEventBus.DragAction.START -> {
                    gestureX = currentX
                    gestureY = currentY
                    val path = Path().apply {
                        moveTo(gestureX, gestureY)
                        lineTo(gestureX, gestureY)
                    }
                    val stroke = GestureDescription.StrokeDescription(path, 0, 10, true)
                    currentDragStroke = stroke
                    dispatchOnDisplay(stroke)
                }
                TrackpadEventBus.DragAction.MOVE -> {
                    val oldX = gestureX
                    val oldY = gestureY

                    if (event.isScroll) {
                        gestureX += event.dx
                        gestureY += event.dy
                    } else {
                        currentX = (currentX + event.dx).coerceIn(0f, displayWidth.toFloat() - 1f)
                        currentY = (currentY + event.dy).coerceIn(0f, displayHeight.toFloat() - 1f)
                        updateCursorPosition()
                        gestureX = currentX
                        gestureY = currentY
                    }

                    // Coerce gesture coordinates to prevent out-of-bounds crashes
                    gestureX = gestureX.coerceIn(0f, displayWidth.toFloat() - 1f)
                    gestureY = gestureY.coerceIn(0f, displayHeight.toFloat() - 1f)

                    val stroke = currentDragStroke ?: return
                    val path = Path().apply {
                        moveTo(oldX, oldY)
                        lineTo(gestureX, gestureY)
                    }
                    val nextStroke = try {
                        stroke.continueStroke(path, 0, 20, true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error continuing stroke", e)
                        null
                    }
                    if (nextStroke == null) {
                        // Restarting inline can lead to recursion, so just stop and wait for next drag
                        currentDragStroke = null
                        return
                    }
                    currentDragStroke = nextStroke
                    dispatchOnDisplay(nextStroke)
                }
                TrackpadEventBus.DragAction.STOP -> {
                    val stroke = currentDragStroke ?: return
                    val path = Path().apply {
                        moveTo(gestureX, gestureY)
                        lineTo(gestureX, gestureY)
                    }
                    val lastStroke = try {
                        stroke.continueStroke(path, 0, 10, false)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping stroke", e)
                        null
                    }
                    lastStroke?.let { dispatchOnDisplay(it) }
                    currentDragStroke = null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error in handleDragEvent", e)
        }
    }

    private fun performClick(x: Float, y: Float, displayId: Int) {
        dispatchTap(x, y, displayId, durationMs = 50L)
    }

    /** Right click: Android maps a long press to the context menu / secondary action. */
    private fun performLongPress(x: Float, y: Float, displayId: Int) {
        dispatchTap(x, y, displayId, durationMs = ViewConfiguration.getLongPressTimeout() + 150L)
    }

    private fun dispatchTap(x: Float, y: Float, displayId: Int, durationMs: Long) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y) // Ensure path is not empty
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .setDisplayId(displayId) // API 30+ (minSdk) can target a specific display
            .build()
        dispatchGesture(gesture, null, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used for input injection, required to be overridden
    }

    override fun onInterrupt() {
        // Not used
    }

    override fun onDestroy() {
        displayManager?.unregisterDisplayListener(displayListener)
        serviceScope.cancel()
        cursorView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing cursor on destroy", e)
            }
        }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "ClickInjectorService"
    }
}
