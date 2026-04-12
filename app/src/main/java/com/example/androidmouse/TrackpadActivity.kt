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
        TrackpadEventBus.sendDragEvent(TrackpadEventBus.DragAction.START)
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

        findViewById<View>(R.id.btn_back).setOnClickListener {
            TrackpadEventBus.sendGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
        findViewById<View>(R.id.btn_home).setOnClickListener {
            TrackpadEventBus.sendGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
        findViewById<View>(R.id.btn_recents).setOnClickListener {
            TrackpadEventBus.sendGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        }

        setupDisplaySpinner()
        setupSensitivitySlider()
    }

    private fun setupSensitivitySlider() {
        val sharedPrefs = getSharedPreferences("trackpad_prefs", Context.MODE_PRIVATE)
        sensitivity = sharedPrefs.getFloat("sensitivity", 1.0f)
        
        val seekBar = findViewById<SeekBar>(R.id.sensitivity_seekbar)
        // SeekBar is 0-100, we want sensitivity to be roughly 0.1 to 3.0
        // (progress + 10) / 50.0f -> if progress is 40, sensitivity is 1.0
        seekBar.progress = (sensitivity * 50f - 10f).toInt().coerceIn(0, 100)
        
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                sensitivity = (progress + 10) / 50.0f
                sharedPrefs.edit { putFloat("sensitivity", sensitivity) }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupDisplaySpinner() {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays
        val displayNames = displays.map { "${it.name} (ID: ${it.displayId})" }
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        
        val spinner = findViewById<Spinner>(R.id.display_spinner)
        spinner.adapter = adapter
        
        // Find current display ID from service if it's already running, 
        // but for now just default to what the service picks.
        // Actually, let's just pick the first external one as default in UI if it exists.
        val externalIndex = displays.indexOfFirst { it.displayId != Display.DEFAULT_DISPLAY }
        if (externalIndex != -1) {
            spinner.setSelection(externalIndex)
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedDisplay = displays[position]
                TrackpadEventBus.sendDisplayChangeEvent(selectedDisplay.displayId)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
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
        val service = "$packageName/${ClickInjectorService::class.java.canonicalName}"
        val enabled = Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED, 0
        )
        if (enabled == 1) {
            val settingValue = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (settingValue != null) {
                val splitter = TextUtils.SimpleStringSplitter(':')
                splitter.setString(settingValue)
                while (splitter.hasNext()) {
                    val accessibilityService = splitter.next()
                    if (accessibilityService.equals(service, ignoreCase = true)) {
                        return true
                    }
                }
            }
        }
        return false
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
                    
                    TrackpadEventBus.sendDragEvent(TrackpadEventBus.DragAction.START)
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

                if (isTwoFinger || isLongPress) {
                    TrackpadEventBus.sendDragEvent(TrackpadEventBus.DragAction.MOVE, dx, dy)
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
                if (isTwoFinger && event.pointerCount < 2) {
                    TrackpadEventBus.sendDragEvent(TrackpadEventBus.DragAction.STOP)
                    isTwoFinger = false
                    // Update lastX/lastY to the remaining finger to prevent jump
                    val remainingIndex = if (event.actionIndex == 0) 1 else 0
                    lastX = event.getX(remainingIndex)
                    lastY = event.getY(remainingIndex)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                val upTime = System.currentTimeMillis()
                val tapTimeout = ViewConfiguration.getTapTimeout().toLong()
                
                if (isLongPress || isTwoFinger) {
                    TrackpadEventBus.sendDragEvent(TrackpadEventBus.DragAction.STOP)
                } else if (isTap && (upTime - downTime) <= tapTimeout) {
                    TrackpadEventBus.sendTapEvent()
                }
                
                isTwoFinger = false
                isLongPress = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                if (isLongPress || isTwoFinger) {
                    TrackpadEventBus.sendDragEvent(TrackpadEventBus.DragAction.STOP)
                }
                isTwoFinger = false
                isLongPress = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
