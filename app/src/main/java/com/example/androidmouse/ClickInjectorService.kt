package com.example.androidmouse

import android.util.Log
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.os.Bundle
import kotlinx.coroutines.*
import kotlin.math.abs

class ClickInjectorService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var windowManager: WindowManager? = null
    private var cursorView: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // Track the absolute position of the cursor
    private var currentX = 0f
    private var currentY = 0f
    private var cursorSize = 0
    private var externalDisplayId: Int = Display.DEFAULT_DISPLAY
    private var displayWidth = 1920
    private var displayHeight = 1080

    private var currentDragStroke: GestureDescription.StrokeDescription? = null
    private var gestureX = 0f
    private var gestureY = 0f

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("ClickInjectorService", "Accessibility Service Connected")
        setupOverlay()
        observeEvents()
    }

    private fun setupOverlay(displayId: Int? = null) {
        try {
            cursorView?.let {
                try {
                    windowManager?.removeView(it)
                } catch (e: Exception) {
                    Log.e("ClickInjectorService", "Error removing old cursor view", e)
                }
            }

            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val displays = displayManager.displays
            
            val targetDisplay = if (displayId != null) {
                displays.firstOrNull { it.displayId == displayId }
            } else {
                displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY } 
                                 ?: displays.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY }
            }
            
            if (targetDisplay == null) return
            
            externalDisplayId = targetDisplay.displayId
            val size = android.graphics.Point()
            targetDisplay.getRealSize(size)
            displayWidth = size.x
            displayHeight = size.y

            currentX = displayWidth / 2f
            currentY = displayHeight / 2f

            val displayContext = createDisplayContext(targetDisplay)
            windowManager = displayContext.getSystemService(WINDOW_SERVICE) as WindowManager
            cursorSize = resources.getDimensionPixelSize(R.dimen.cursor_size)

            cursorView = ImageView(this).apply {
                setImageResource(R.drawable.cursor)
                alpha = 1.0f
            }

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
        } catch (e: Exception) {
            Log.e("ClickInjectorService", "Error in setupOverlay", e)
        }
    }

    private fun observeEvents() {
        serviceScope.launch {
            TrackpadEventBus.moveEvents.collect { (dx, dy) ->
                currentX += dx
                currentY += dy

                currentX = currentX.coerceIn(0f, displayWidth.toFloat() - 1f)
                currentY = currentY.coerceIn(0f, displayHeight.toFloat() - 1f)

                updateCursorPosition()
            }
        }
        serviceScope.launch {
            TrackpadEventBus.tapEvents.collect {
                performClick(currentX, currentY, externalDisplayId)
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
                performClick(currentX, currentY, externalDisplayId)
                delay(200)

                performGlobalAction(action)
            }
        }
        serviceScope.launch {
            TrackpadEventBus.textEvents.collect { text ->
                injectText(text)
            }
        }
    }

    private fun injectText(text: String) {
        val focusedNode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowsByDisplay = getWindowsOnAllDisplays()
            val windows = windowsByDisplay[externalDisplayId]
            var found: AccessibilityNodeInfo? = null
            windows?.forEach { window ->
                if (found == null) {
                    val root = window.root
                    if (root != null) {
                        found = findFocusedNode(root)
                    }
                }
            }
            found
        } else {
            findFocusedNode(rootInActiveWindow)
        }

        if (focusedNode != null) {
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } else {
            Log.d("ClickInjectorService", "No focused node found to inject text")
        }
    }

    private fun findFocusedNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        
        // Check if root itself is focused
        if (root.isFocused) return root
        
        // Search children
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            val focused = findFocusedNode(child)
            if (focused != null) {
                return focused
            }
        }
        return null
    }

    private fun updateCursorPosition() {
        layoutParams?.x = currentX.toInt()
        layoutParams?.y = currentY.toInt()
        
        cursorView?.let { view ->
            try {
                windowManager?.updateViewLayout(view, layoutParams)
            } catch (e: Exception) {
                Log.e("ClickInjectorService", "Error updating cursor position", e)
            }
        }
    }

    private fun handleDragEvent(event: TrackpadEventBus.DragEvent) {
        when (event.action) {
            TrackpadEventBus.DragAction.START -> {
                gestureX = currentX
                gestureY = currentY
                val path = Path().apply { moveTo(gestureX, gestureY) }
                // Gesture with isContinued = true to allow for multiple movements
                // We use a small duration for the initial point
                currentDragStroke = GestureDescription.StrokeDescription(path, 0, 10, true)
                val builder = GestureDescription.Builder().addStroke(currentDragStroke!!)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.setDisplayId(externalDisplayId)
                }
                dispatchGesture(builder.build(), null, null)
            }
            TrackpadEventBus.DragAction.MOVE -> {
                val oldX = gestureX
                val oldY = gestureY

                if (event.isScroll) {
                    // Scrolling: Move the gesture path, but keep cursor in place
                    // Note: Inverting dy for natural scroll feel if needed, but let's keep it direct for now
                    gestureX += event.dx
                    gestureY += event.dy
                } else {
                    // Resizing/Dragging: Move both cursor and gesture path
                    currentX += event.dx
                    currentY += event.dy
                    currentX = currentX.coerceIn(0f, displayWidth.toFloat() - 1f)
                    currentY = currentY.coerceIn(0f, displayHeight.toFloat() - 1f)
                    updateCursorPosition()
                    gestureX = currentX
                    gestureY = currentY
                }

                currentDragStroke?.let { stroke ->
                    val path = Path().apply {
                        moveTo(oldX, oldY)
                        lineTo(gestureX, gestureY)
                    }
                    // Use a short duration for MOVE segments to keep it responsive
                    val nextStroke = stroke.continueStroke(path, 0, 20, true)
                    currentDragStroke = nextStroke
                    val builder = GestureDescription.Builder().addStroke(nextStroke)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        builder.setDisplayId(externalDisplayId)
                    }
                    dispatchGesture(builder.build(), null, null)
                }
            }
            TrackpadEventBus.DragAction.STOP -> {
                currentDragStroke?.let { stroke ->
                    val path = Path().apply { moveTo(gestureX, gestureY) }
                    // Final segment with isContinued = false
                    val lastStroke = stroke.continueStroke(path, 0, 10, false)
                    val builder = GestureDescription.Builder().addStroke(lastStroke)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        builder.setDisplayId(externalDisplayId)
                    }
                    dispatchGesture(builder.build(), null, null)
                    currentDragStroke = null
                }
            }
        }
    }

    private fun performClick(x: Float, y: Float, displayId: Int) {
        val path = Path().apply {
            moveTo(x, y)
        }
        
        // Tap gesture: 0 delay, duration of 50ms is standard for a quick tap
        val strokeDescription = GestureDescription.StrokeDescription(path, 0, 50)
        
        val gestureBuilder = GestureDescription.Builder()
            .addStroke(strokeDescription)
            
        // Require API 30+ to inject gestures onto a specific display
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            gestureBuilder.setDisplayId(displayId)
        }
        
        val gesture = gestureBuilder.build()
        
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                // Gesture successfully executed
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                // Gesture cancelled
            }
        }, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used for input injection, required to be overridden
    }

    override fun onInterrupt() {
        // Not used
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
