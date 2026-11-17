package com.example.androidmouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ClickInjectorService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var eventJob: Job? = null

    private lateinit var displayManager: DisplayManager
    private var displayListenerRegistered = false

    // Target display
    private var targetDisplayId = Display.DEFAULT_DISPLAY
    private var displayWidth = 1920
    private var displayHeight = 1080

    // Overlay
    private var overlayWindowManager: WindowManager? = null
    private var overlayRoot: FrameLayout? = null
    private var cursorView: ImageView? = null
    private var cursorSize = 0

    // Cursor position in target display pixels
    private var cursorX = 0f
    private var cursorY = 0f

    // Drag / scroll
    private val dragTracker = DragTracker(displayWidth, displayHeight)
    private var dragStroke: GestureDescription.StrokeDescription? = null
    private var dragDisplayId = Display.DEFAULT_DISPLAY
    private var dragIsScroll = false
    private var dragGeneration = 0

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            if (targetDisplayId == Display.DEFAULT_DISPLAY && displayId != Display.DEFAULT_DISPLAY) {
                displayManager.getDisplay(displayId)?.let { switchToDisplay(it) }
            }
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == targetDisplayId) {
                pickDisplay(null)?.let { switchToDisplay(it) }
            }
        }

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == targetDisplayId) refreshTargetDisplay()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        if (!displayListenerRegistered) {
            displayManager.registerDisplayListener(displayListener, mainHandler)
            displayListenerRegistered = true
        }

        val savedId = getSharedPreferences(TrackpadConstants.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(TrackpadConstants.KEY_LAST_DISPLAY_ID, -1)
            .takeIf { it >= 0 }
        pickDisplay(savedId)?.let { switchToDisplay(it) }

        eventJob?.cancel()
        eventJob = serviceScope.launch {
            TrackpadEventBus.events.collect { event ->
                try {
                    handleEvent(event)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling $event", e)
                }
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun shutdown() {
        eventJob?.cancel()
        eventJob = null
        if (displayListenerRegistered) {
            displayManager.unregisterDisplayListener(displayListener)
            displayListenerRegistered = false
        }
        abortDrag()
        removeOverlay()
        TrackpadEventBus.setActiveDisplayId(null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Events are not used; the service only injects input.
    }

    override fun onInterrupt() {
        // Not used
    }

    // ---------------------------------------------------------------- events

    private fun handleEvent(event: TrackpadEvent): Unit = when (event) {
        is TrackpadEvent.Move -> moveCursorBy(event.dx, event.dy)
        TrackpadEvent.Tap -> {
            abortDrag()
            performClick()
        }
        is TrackpadEvent.DragStart -> {
            abortDrag()
            startDrag(event.isScroll)
        }
        is TrackpadEvent.DragMove -> dragMove(event.dx, event.dy)
        TrackpadEvent.DragStop -> runSteps(dragTracker.stop())
        is TrackpadEvent.GlobalAction -> {
            abortDrag()
            globalAction(event.action)
        }
        is TrackpadEvent.SelectDisplay -> selectDisplay(event.displayId)
        is TrackpadEvent.Text -> insertText(event.text)
        TrackpadEvent.Backspace -> backspace()
        TrackpadEvent.Enter -> pressEnter()
    }

    private fun globalAction(action: Int) {
        performGlobalAction(action)
    }

    // ---------------------------------------------------------------- displays

    /** Preferred display if it exists, else first external display, else the default display. */
    private fun pickDisplay(preferredId: Int?): Display? {
        if (preferredId != null) {
            displayManager.getDisplay(preferredId)?.let { return it }
        }
        return displayManager.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
            ?: displayManager.getDisplay(Display.DEFAULT_DISPLAY)
    }

    private fun selectDisplay(displayId: Int) {
        if (displayId == targetDisplayId) return
        val display = displayManager.getDisplay(displayId) ?: return
        switchToDisplay(display)
    }

    private fun switchToDisplay(display: Display) {
        abortDrag()
        val size = displaySize(display)
        if (size == null) {
            Log.e(TAG, "Cannot determine size of display ${display.displayId}")
            return
        }
        targetDisplayId = display.displayId
        displayWidth = size.first
        displayHeight = size.second
        dragTracker.setBounds(displayWidth, displayHeight)
        cursorX = displayWidth / 2f
        cursorY = displayHeight / 2f
        buildOverlay(display)
        TrackpadEventBus.setActiveDisplayId(targetDisplayId)
        Log.d(TAG, "Targeting display $targetDisplayId (${displayWidth}x$displayHeight)")
    }

    private fun refreshTargetDisplay() {
        val display = displayManager.getDisplay(targetDisplayId) ?: return
        val size = displaySize(display) ?: return
        if (size.first == displayWidth && size.second == displayHeight) return
        abortDrag()
        displayWidth = size.first
        displayHeight = size.second
        dragTracker.setBounds(displayWidth, displayHeight)
        cursorX = cursorX.coerceIn(0f, displayWidth - 1f)
        cursorY = cursorY.coerceIn(0f, displayHeight - 1f)
        buildOverlay(display)
    }

    private fun windowManagerFor(display: Display): Pair<Context, WindowManager> {
        val ctx = createDisplayContext(display)
        return ctx to ctx.getSystemService(WindowManager::class.java)
    }

    private fun displaySize(display: Display): Pair<Int, Int>? {
        return try {
            val bounds = windowManagerFor(display).second.currentWindowMetrics.bounds
            if (bounds.width() > 0 && bounds.height() > 0) {
                bounds.width() to bounds.height()
            } else {
                legacyDisplaySize(display)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading window metrics, falling back to Display size", e)
            legacyDisplaySize(display)
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyDisplaySize(display: Display): Pair<Int, Int>? {
        val size = Point()
        display.getRealSize(size)
        return if (size.x > 0 && size.y > 0) size.x to size.y else null
    }

    // ---------------------------------------------------------------- overlay / cursor

    private fun buildOverlay(display: Display) {
        removeOverlay()
        try {
            val (ctx, wm) = windowManagerFor(display)
            cursorSize = ctx.resources.getDimensionPixelSize(R.dimen.cursor_size)

            val cursor = ImageView(ctx).apply {
                setImageResource(R.drawable.cursor)
                scaleType = ImageView.ScaleType.FIT_START
                layoutParams = FrameLayout.LayoutParams(cursorSize, cursorSize, Gravity.TOP or Gravity.START)
            }
            val root = FrameLayout(ctx).apply {
                clipChildren = false
                clipToPadding = false
                // Never consume or pad for system insets: (0,0) must be the display's (0,0).
                setOnApplyWindowInsetsListener { _, insets -> insets }
                addView(cursor)
            }

            val lp = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
            }

            cursor.translationX = cursorX
            cursor.translationY = cursorY
            wm.addView(root, lp)

            overlayWindowManager = wm
            overlayRoot = root
            cursorView = cursor
        } catch (e: Exception) {
            Log.e(TAG, "Error creating overlay", e)
            overlayWindowManager = null
            overlayRoot = null
            cursorView = null
        }
    }

    private fun removeOverlay() {
        val root = overlayRoot
        if (root != null) {
            try {
                overlayWindowManager?.removeView(root)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay", e)
            }
        }
        overlayRoot = null
        overlayWindowManager = null
        cursorView = null
    }

    private fun moveCursorBy(dx: Float, dy: Float) {
        cursorX = (cursorX + dx).coerceIn(0f, displayWidth - 1f)
        cursorY = (cursorY + dy).coerceIn(0f, displayHeight - 1f)
        cursorView?.let {
            it.translationX = cursorX
            it.translationY = cursorY
        }
    }

    // ---------------------------------------------------------------- tap

    private fun performClick() {
        val path = Path().apply {
            moveTo(cursorX, cursorY)
            lineTo(cursorX, cursorY) // Ensure path is not empty
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .setDisplayId(targetDisplayId)
            .build()
        try {
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching tap", e)
        }
    }

    // ---------------------------------------------------------------- drag / scroll

    private fun startDrag(isScroll: Boolean) {
        dragDisplayId = targetDisplayId
        dragIsScroll = isScroll
        dragStroke = null
        runSteps(dragTracker.start(isScroll, cursorX, cursorY))
    }

    private fun dragMove(dx: Float, dy: Float) {
        if (!dragTracker.isActive) return
        // For non-scroll drags the cursor follows the finger (both clamp identically).
        val step = dragTracker.move(dx, dy)
        if (!dragIsScroll) moveCursorBy(dx, dy)
        runSteps(step)
    }

    /** Executes [first] and any immediately-following steps (only produced by dispatch failures). */
    private fun runSteps(first: DragStep?) {
        var step = first
        while (step != null) {
            step = executeStep(step)
        }
    }

    /** Dispatches one step. Returns the tracker's follow-up step if dispatching failed at once. */
    private fun executeStep(step: DragStep): DragStep? {
        val generation = dragGeneration
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (generation == dragGeneration) runSteps(dragTracker.onCompleted())
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (generation == dragGeneration) runSteps(dragTracker.onFailed())
            }
        }
        return if (dispatchStep(step, callback)) null else dragTracker.onFailed()
    }

    private fun dispatchStep(step: DragStep, callback: GestureResultCallback?): Boolean {
        return try {
            val stroke = when (step) {
                is DragStep.Down -> GestureDescription.StrokeDescription(
                    segmentPath(step.x, step.y, step.x, step.y), 0, DOWN_DURATION_MS, true
                )
                is DragStep.Move -> requireNotNull(dragStroke) { "no stroke to continue" }
                    .continueStroke(
                        segmentPath(step.fromX, step.fromY, step.toX, step.toY),
                        0, SEGMENT_DURATION_MS, true
                    )
                is DragStep.Lift -> requireNotNull(dragStroke) { "no stroke to continue" }
                    .continueStroke(
                        segmentPath(step.x, step.y, step.x, step.y), 0, LIFT_DURATION_MS, false
                    )
            }
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .setDisplayId(dragDisplayId)
                .build()
            val accepted = dispatchGesture(gesture, callback, mainHandler)
            if (accepted) dragStroke = stroke
            accepted
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching drag step $step", e)
            false
        }
    }

    private fun segmentPath(fromX: Float, fromY: Float, toX: Float, toY: Float): Path {
        val maxX = displayWidth - 1f
        val maxY = displayHeight - 1f
        return Path().apply {
            moveTo(fromX.coerceIn(0f, maxX), fromY.coerceIn(0f, maxY))
            lineTo(toX.coerceIn(0f, maxX), toY.coerceIn(0f, maxY))
        }
    }

    /** Ends any active drag: lifts the finger if possible and ignores stale callbacks. */
    private fun abortDrag() {
        val lift = dragTracker.abort()
        dragGeneration++
        if (lift != null) dispatchStep(lift, null)
        dragStroke = null
    }

    // ---------------------------------------------------------------- text input

    private fun focusedInputNode(): AccessibilityNodeInfo? {
        val windows = getWindowsOnAllDisplays().get(targetDisplayId) ?: return null
        for (window in windows) {
            val root = window.root ?: continue
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
            // Re-fetch from the app: the cached copy can hold text from before our last edit,
            // which would drop characters when typing quickly.
            if (focused.refresh()) return focused
        }
        return null
    }

    private fun nodeText(node: AccessibilityNodeInfo): String =
        if (node.isShowingHintText) "" else node.text?.toString() ?: ""

    private fun insertText(text: String) {
        val node = focusedInputNode()
        if (node == null) {
            Log.d(TAG, "No focused node found to inject text")
            return
        }
        val result = TextEditing.insert(
            nodeText(node), node.textSelectionStart, node.textSelectionEnd, text
        )
        applyEdit(node, result)
    }

    private fun backspace() {
        val node = focusedInputNode() ?: return
        val result = TextEditing.backspace(
            nodeText(node), node.textSelectionStart, node.textSelectionEnd
        ) ?: return
        applyEdit(node, result)
    }

    private fun applyEdit(node: AccessibilityNodeInfo, result: TextEditing.Result) {
        val setText = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, result.text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setText)
        val setSelection = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, result.caret)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, result.caret)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, setSelection)
    }

    private fun pressEnter() {
        val node = focusedInputNode() ?: return
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    companion object {
        private const val TAG = "ClickInjectorService"
        private const val TAP_DURATION_MS = 50L
        private const val DOWN_DURATION_MS = 10L
        private const val SEGMENT_DURATION_MS = 15L
        private const val LIFT_DURATION_MS = 20L
    }
}
