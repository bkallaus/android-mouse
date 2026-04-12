package com.example.androidmouse

import android.util.Log
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.core.content.edit
import kotlin.math.abs

class TrackpadActivity : Activity() {

    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var isTap = false
    private var isLongPress = false
    private var isTwoFinger = false
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        isLongPress = true
        TrackpadEventBus.sendDragEvent(TrackpadEventBus.DragAction.START, isScroll = false)
        // Feedback
        window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    private var touchSlop = 0
    private var sensitivity = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trackpad)
        
        // Ensure the screen stays awake while using the trackpad
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        
        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        findViewById<View>(R.id.btn_recents).setOnClickListener {
            TrackpadEventBus.sendGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        }

        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
            showSettingsDialog()
        }

        setupKeyboard()
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val spinner = dialogView.findViewById<Spinner>(R.id.dialog_display_spinner)
        val seekBar = dialogView.findViewById<SeekBar>(R.id.dialog_sensitivity_seekbar)

        // Setup Spinner
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays
        val displayNames = displays.map { "${it.name} (ID: ${it.displayId})" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val sharedPrefs = getSharedPreferences(TrackpadConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val currentDisplayIndex = displays.indexOfFirst { it.displayId == sharedPrefs.getInt(TrackpadConstants.KEY_LAST_DISPLAY_ID, -1) }
        if (currentDisplayIndex != -1) {
            spinner.setSelection(currentDisplayIndex)
        } else {
            val externalIndex = displays.indexOfFirst { it.displayId != Display.DEFAULT_DISPLAY }
            if (externalIndex != -1) spinner.setSelection(externalIndex)
        }

        // Setup Sensitivity
        sensitivity = sharedPrefs.getFloat(TrackpadConstants.KEY_SENSITIVITY, TrackpadConstants.DEFAULT_SENSITIVITY)
        seekBar.progress = (sensitivity * 50f - 10f).toInt().coerceIn(0, 100)

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
                sharedPrefs.edit { putInt(TrackpadConstants.KEY_LAST_DISPLAY_ID, selectedDisplay.displayId) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                sensitivity = (progress + 10) / 50.0f
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                sharedPrefs.edit { putFloat(TrackpadConstants.KEY_SENSITIVITY, sensitivity) }
            }
        })

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
                }
                // Optional: hide keyboard after sending? 
                // imm.hideSoftInputFromWindow(v.windowToken, 0)
                true
            } else {
                false
            }
        }
        
        // Listen for backspace if the field is empty? 
        // That's tricky with ACTION_SET_TEXT on the other side.
        // For now, let's focus on sending blocks of text.
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndStartService()
    }
    
    private fun checkPermissionsAndStartService() {
        Log.d("TrackpadActivity", "checkPermissionsAndStartService")
        val overlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

        val accessibilityPermission = isAccessibilityServiceEnabled()
        Log.d("TrackpadActivity", "overlayPermission=$overlayPermission, accessibilityPermission=$accessibilityPermission")

        val statusText = findViewById<TextView>(R.id.status_text)
        val accessibilityButton = findViewById<Button>(R.id.btn_accessibility)
        statusText.visibility = View.VISIBLE

        if (!overlayPermission) {
            Log.d("TrackpadActivity", "Overlay permission missing")
            statusText.text = "Overlay permission required"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        } else if (!accessibilityPermission) {
            Log.d("TrackpadActivity", "Accessibility permission missing")
            statusText.text = "Accessibility service required"
            accessibilityButton.visibility = View.VISIBLE
        } else {
            Log.d("TrackpadActivity", "Permissions OK")
            statusText.text = "Trackpad Active\nMove finger to control cursor\nTap to click"
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                lastX = event.x
                lastY = event.y
                downTime = System.currentTimeMillis()
                isTap = true
                isLongPress = false
                isTwoFinger = false
                longPressHandler.postDelayed(longPressRunnable, longPressTimeout)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    isTwoFinger = true
                    isTap = false
                    isLongPress = false
                    longPressHandler.removeCallbacks(longPressRunnable)
                    
                    TrackpadEventBus.sendDragEvent(TrackpadEventBus.DragAction.START, isScroll = true)
                    // Feedback
                    window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    
                    // Reset lastX/lastY to the average of the two fingers for smoother drag start
                    lastX = (event.getX(0) + event.getX(1)) / 2f
                    lastY = (event.getY(0) + event.getY(1)) / 2f
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val currentX = if (isTwoFinger && event.pointerCount >= 2) {
                    (event.getX(0) + event.getX(1)) / 2f
                } else {
                    event.x
                }
                val currentY = if (isTwoFinger && event.pointerCount >= 2) {
                    (event.getY(0) + event.getY(1)) / 2f
                } else {
                    event.y
                }

                val dx = (currentX - lastX) * sensitivity
                val dy = (currentY - lastY) * sensitivity

                if (isTwoFinger) {
                    if (dx != 0f || dy != 0f) {
                        TrackpadEventBus.sendDragEvent(TrackpadEventBus.DragAction.MOVE, dx, dy, isScroll = true)
                    }
                } else if (isLongPress) {
                    if (dx != 0f || dy != 0f) {
                        TrackpadEventBus.sendDragEvent(TrackpadEventBus.DragAction.MOVE, dx, dy, isScroll = false)
                    }
                } else {
                    if (dx != 0f || dy != 0f) {
                        TrackpadEventBus.sendMoveEvent(dx, dy)
                    }
                    
                    if (isTap && (abs(event.x - startX) > touchSlop || abs(event.y - startY) > touchSlop)) {
                        isTap = false
                        longPressHandler.removeCallbacks(longPressRunnable)
                    }
                }
                
                lastX = currentX
                lastY = currentY
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (isTwoFinger) {
                    TrackpadEventBus.sendDragEvent(TrackpadEventBus.DragAction.STOP, isScroll = true)
                    isTwoFinger = false
                    
                    // Update lastX/lastY to the remaining finger to prevent jump
                    val upIndex = event.actionIndex
                    val remainingIndex = if (upIndex == 0) 1 else 0
                    if (remainingIndex < event.pointerCount) {
                        try {
                            lastX = event.getX(remainingIndex)
                            lastY = event.getY(remainingIndex)
                        } catch (e: IllegalArgumentException) {
                            Log.e("TrackpadActivity", "Error getting pointer coordinates", e)
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                
                if (isLongPress) {
                    TrackpadEventBus.sendDragEvent(TrackpadEventBus.DragAction.STOP, isScroll = false)
                } else if (isTwoFinger) {
                    TrackpadEventBus.sendDragEvent(TrackpadEventBus.DragAction.STOP, isScroll = true)
                } else if (isTap) {
                    TrackpadEventBus.sendTapEvent()
                }
                
                isTwoFinger = false
                isLongPress = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                if (isLongPress) {
                    TrackpadEventBus.sendDragEvent(TrackpadEventBus.DragAction.STOP, isScroll = false)
                } else if (isTwoFinger) {
                    TrackpadEventBus.sendDragEvent(TrackpadEventBus.DragAction.STOP, isScroll = true)
                }
                isTwoFinger = false
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
}
