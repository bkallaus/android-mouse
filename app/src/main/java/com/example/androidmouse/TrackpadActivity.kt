package com.example.androidmouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.edit
import kotlin.math.abs
import kotlin.math.hypot

class TrackpadActivity : Activity() {

    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastMoveTime = 0L
    private var isTap = false
    private var isLongPress = false

    // Two-finger state: a short, still two-finger touch is a right click; movement scrolls
    private var isTwoFinger = false
    private var isScrolling = false
    private var twoFingerDownTime = 0L
    private var twoFingerStartX = 0f
    private var twoFingerStartY = 0f

    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        isLongPress = true
        isTap = false
        TrackpadEventBus.sendDragEvent(TrackpadEventBus.DragAction.START, isScroll = false)
        haptic(HapticFeedbackConstants.LONG_PRESS)
    }

    private var touchSlop = 0
    private var sensitivity = TrackpadConstants.DEFAULT_SENSITIVITY
    private var accelerationEnabled = true
    private var reverseScroll = false

    private val prefs by lazy { getSharedPreferences(TrackpadConstants.PREFS_NAME, Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trackpad)

        // Ensure the screen stays awake while using the trackpad
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        bindGlobalAction(R.id.btn_back, AccessibilityService.GLOBAL_ACTION_BACK)
        bindGlobalAction(R.id.btn_home, AccessibilityService.GLOBAL_ACTION_HOME)
        bindGlobalAction(R.id.btn_recents, AccessibilityService.GLOBAL_ACTION_RECENTS)

        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
            showSettingsDialog()
        }

        setupKeyboard()
    }

    private fun bindGlobalAction(viewId: Int, action: Int) {
        findViewById<View>(viewId).setOnClickListener {
            haptic(HapticFeedbackConstants.VIRTUAL_KEY)
            TrackpadEventBus.sendGlobalAction(action)
        }
    }

    private fun loadPrefs() {
        sensitivity = prefs.getFloat(TrackpadConstants.KEY_SENSITIVITY, TrackpadConstants.DEFAULT_SENSITIVITY)
        accelerationEnabled = prefs.getBoolean(TrackpadConstants.KEY_ACCELERATION, true)
        reverseScroll = prefs.getBoolean(TrackpadConstants.KEY_REVERSE_SCROLL, false)
    }

    private fun haptic(type: Int) {
        window.decorView.performHapticFeedback(type)
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val spinner = dialogView.findViewById<Spinner>(R.id.dialog_display_spinner)
        val seekBar = dialogView.findViewById<SeekBar>(R.id.dialog_sensitivity_seekbar)
        val accelSwitch = dialogView.findViewById<CompoundButton>(R.id.dialog_acceleration_switch)
        val reverseSwitch = dialogView.findViewById<CompoundButton>(R.id.dialog_reverse_scroll_switch)

        // Setup Spinner
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays
        val displayNames = displays.map { "${it.name} (ID: ${it.displayId})" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val currentDisplayIndex = displays.indexOfFirst {
            it.displayId == prefs.getInt(TrackpadConstants.KEY_LAST_DISPLAY_ID, -1)
        }
        if (currentDisplayIndex != -1) {
            spinner.setSelection(currentDisplayIndex)
        } else {
            val externalIndex = displays.indexOfFirst { it.displayId != Display.DEFAULT_DISPLAY }
            if (externalIndex != -1) spinner.setSelection(externalIndex)
        }

        seekBar.progress = (sensitivity * 50f - 10f).toInt().coerceIn(0, 100)
        accelSwitch.isChecked = accelerationEnabled
        reverseSwitch.isChecked = reverseScroll

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(dialogView)
            .create()

        // Make the dialog wider (set before show to avoid flicker)
        dialog.window?.let { window ->
            val params = window.attributes
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            window.attributes = params
        }

        // Guard against spinner auto-firing onItemSelected on attach
        var userInitiatedSpinner = false
        spinner.post { userInitiatedSpinner = true }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!userInitiatedSpinner) return
                val selectedDisplay = displays[position]
                TrackpadEventBus.sendDisplayChangeEvent(selectedDisplay.displayId)
                prefs.edit { putInt(TrackpadConstants.KEY_LAST_DISPLAY_ID, selectedDisplay.displayId) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                sensitivity = (progress + 10) / 50.0f
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                prefs.edit { putFloat(TrackpadConstants.KEY_SENSITIVITY, sensitivity) }
            }
        })

        accelSwitch.setOnCheckedChangeListener { _, checked ->
            accelerationEnabled = checked
            prefs.edit { putBoolean(TrackpadConstants.KEY_ACCELERATION, checked) }
        }
        reverseSwitch.setOnCheckedChangeListener { _, checked ->
            reverseScroll = checked
            prefs.edit { putBoolean(TrackpadConstants.KEY_REVERSE_SCROLL, checked) }
        }

        dialog.show()

        dialogView.findViewById<View>(R.id.btn_close_settings).setOnClickListener {
            dialog.dismiss()
        }
    }

    private fun setupKeyboard() {
        val hiddenEditText = findViewById<EditText>(R.id.hidden_edit_text)
        val btnKeyboard = findViewById<ImageButton>(R.id.btn_keyboard)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        btnKeyboard.setOnClickListener {
            hiddenEditText.requestFocus()
            imm.showSoftInput(hiddenEditText, InputMethodManager.SHOW_IMPLICIT)
        }

        hiddenEditText.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                val text = v.text.toString()
                if (text.isNotEmpty()) {
                    TrackpadEventBus.sendTextEvent(text)
                    v.text = ""
                } else {
                    // Empty field + Send = press Enter on the target (submit forms, search, etc.)
                    TrackpadEventBus.sendKeyEvent(KeyEvent.KEYCODE_ENTER)
                }
                true
            } else {
                false
            }
        }

        // Backspace on the (empty) local buffer deletes on the external display instead
        hiddenEditText.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                keyCode == KeyEvent.KEYCODE_DEL &&
                hiddenEditText.text.isEmpty()
            ) {
                TrackpadEventBus.sendKeyEvent(KeyEvent.KEYCODE_DEL)
                true
            } else {
                false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadPrefs()
        updatePermissionStatus()
    }

    // The cursor uses TYPE_ACCESSIBILITY_OVERLAY, so the accessibility service is the only
    // permission needed; "Display over other apps" is not required.
    private fun updatePermissionStatus() {
        val statusText = findViewById<TextView>(R.id.status_text)
        val accessibilityButton = findViewById<Button>(R.id.btn_accessibility)
        statusText.visibility = View.VISIBLE

        if (!isAccessibilityServiceEnabled()) {
            Log.d(TAG, "Accessibility permission missing")
            statusText.setText(R.string.status_needs_accessibility)
            accessibilityButton.visibility = View.VISIBLE
        } else {
            statusText.setText(R.string.status_active)
            accessibilityButton.visibility = View.GONE
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
                it.resolveInfo.serviceInfo.name == ClickInjectorService::class.java.canonicalName
        }
    }

    private fun resetTwoFinger() {
        isTwoFinger = false
        isScrolling = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                lastX = event.x
                lastY = event.y
                lastMoveTime = event.eventTime
                isTap = true
                isLongPress = false
                resetTwoFinger()
                longPressHandler.postDelayed(longPressRunnable, longPressTimeout)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger during a long-press drag must not hijack the in-flight drag stroke
                if (event.pointerCount == 2 && !isLongPress) {
                    isTwoFinger = true
                    isScrolling = false
                    isTap = false
                    longPressHandler.removeCallbacks(longPressRunnable)

                    twoFingerDownTime = event.eventTime
                    twoFingerStartX = (event.getX(0) + event.getX(1)) / 2f
                    twoFingerStartY = (event.getY(0) + event.getY(1)) / 2f
                    lastX = twoFingerStartX
                    lastY = twoFingerStartY
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val twoFingerActive = isTwoFinger && event.pointerCount >= 2
                val currentX = if (twoFingerActive) (event.getX(0) + event.getX(1)) / 2f else event.x
                val currentY = if (twoFingerActive) (event.getY(0) + event.getY(1)) / 2f else event.y
                val rawDx = currentX - lastX
                val rawDy = currentY - lastY
                val dt = event.eventTime - lastMoveTime

                if (isTwoFinger) {
                    if (!isScrolling) {
                        // Only become a scroll once the fingers really move; otherwise it may be a right click
                        if (hypot(currentX - twoFingerStartX, currentY - twoFingerStartY) > touchSlop) {
                            isScrolling = true
                            TrackpadEventBus.sendDragEvent(TrackpadEventBus.DragAction.START, isScroll = true)
                            haptic(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    } else if (rawDx != 0f || rawDy != 0f) {
                        val dir = if (reverseScroll) -1f else 1f
                        TrackpadEventBus.sendDragEvent(
                            TrackpadEventBus.DragAction.MOVE,
                            rawDx * sensitivity * dir,
                            rawDy * sensitivity * dir,
                            isScroll = true
                        )
                    }
                } else if (rawDx != 0f || rawDy != 0f) {
                    val gain = PointerAcceleration.gain(rawDx, rawDy, dt, accelerationEnabled)
                    val dx = rawDx * sensitivity * gain
                    val dy = rawDy * sensitivity * gain
                    if (isLongPress) {
                        TrackpadEventBus.sendDragEvent(TrackpadEventBus.DragAction.MOVE, dx, dy, isScroll = false)
                    } else {
                        TrackpadEventBus.sendMoveEvent(dx, dy)
                        if (isTap && (abs(event.x - startX) > touchSlop || abs(event.y - startY) > touchSlop)) {
                            isTap = false
                            longPressHandler.removeCallbacks(longPressRunnable)
                        }
                    }
                }

                lastX = currentX
                lastY = currentY
                lastMoveTime = event.eventTime
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (isTwoFinger) {
                    if (isScrolling) {
                        TrackpadEventBus.sendDragEvent(TrackpadEventBus.DragAction.STOP, isScroll = true)
                    } else if (event.eventTime - twoFingerDownTime <= TrackpadConstants.TWO_FINGER_TAP_TIMEOUT_MS) {
                        TrackpadEventBus.sendRightClickEvent()
                        haptic(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                    resetTwoFinger()
                }

                // Continue tracking from the remaining finger to prevent a cursor jump
                if (event.pointerCount == 2) {
                    val remainingIndex = if (event.actionIndex == 0) 1 else 0
                    lastX = event.getX(remainingIndex)
                    lastY = event.getY(remainingIndex)
                    lastMoveTime = event.eventTime
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                longPressHandler.removeCallbacks(longPressRunnable)

                if (isLongPress) {
                    TrackpadEventBus.sendDragEvent(TrackpadEventBus.DragAction.STOP, isScroll = false)
                } else if (isScrolling) {
                    TrackpadEventBus.sendDragEvent(TrackpadEventBus.DragAction.STOP, isScroll = true)
                } else if (isTap) {
                    TrackpadEventBus.sendTapEvent()
                    haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                }

                resetTwoFinger()
                isLongPress = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                if (isLongPress) {
                    TrackpadEventBus.sendDragEvent(TrackpadEventBus.DragAction.STOP, isScroll = false)
                } else if (isScrolling) {
                    TrackpadEventBus.sendDragEvent(TrackpadEventBus.DragAction.STOP, isScroll = true)
                }
                resetTwoFinger()
                isLongPress = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onPause() {
        super.onPause()
        longPressHandler.removeCallbacks(longPressRunnable)
    }

    private companion object {
        const val TAG = "TrackpadActivity"
    }
}
