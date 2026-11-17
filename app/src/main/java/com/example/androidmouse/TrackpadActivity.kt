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
import android.text.Editable
import android.text.Selection
import android.text.TextWatcher
import android.view.Display
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.edit

class TrackpadActivity : Activity() {

    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable { detector.onLongPressTimeout() }

    private lateinit var detector: TrackpadGestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trackpad)

        // Ensure the screen stays awake while using the trackpad
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveMode()

        detector = TrackpadGestureDetector(
            touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat(),
            backAction = AccessibilityService.GLOBAL_ACTION_BACK,
            emit = { TrackpadEventBus.send(it) },
            scheduleLongPress = {
                longPressHandler.removeCallbacks(longPressRunnable)
                longPressHandler.postDelayed(
                    longPressRunnable,
                    ViewConfiguration.getLongPressTimeout().toLong()
                )
            },
            cancelLongPress = { longPressHandler.removeCallbacks(longPressRunnable) },
            performHaptic = {
                window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        )
        detector.sensitivity = getSharedPreferences(TrackpadConstants.PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(TrackpadConstants.KEY_SENSITIVITY, TrackpadConstants.DEFAULT_SENSITIVITY)

        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        bindNavButton(R.id.btn_back, AccessibilityService.GLOBAL_ACTION_BACK)
        bindNavButton(R.id.btn_home, AccessibilityService.GLOBAL_ACTION_HOME)
        bindNavButton(R.id.btn_recents, AccessibilityService.GLOBAL_ACTION_RECENTS)

        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
            showSettingsDialog()
        }

        setupKeyboard()
    }

    private fun bindNavButton(viewId: Int, action: Int) {
        findViewById<View>(viewId).setOnClickListener {
            TrackpadEventBus.send(TrackpadEvent.GlobalAction(action))
        }
    }

    private fun applyImmersiveMode() {
        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val spinner = dialogView.findViewById<Spinner>(R.id.dialog_display_spinner)
        val seekBar = dialogView.findViewById<SeekBar>(R.id.dialog_sensitivity_seekbar)
        val sharedPrefs = getSharedPreferences(TrackpadConstants.PREFS_NAME, Context.MODE_PRIVATE)

        // Setup Spinner
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays
        val displayNames = displays.map {
            getString(R.string.display_name_format, it.name, it.displayId)
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        // Preselect: what the service is really driving, then the saved pref, then the first external display.
        val activeId = TrackpadEventBus.activeDisplayId.value
        var initialIndex = if (activeId != null) displays.indexOfFirst { it.displayId == activeId } else -1
        if (initialIndex == -1) {
            val savedId = sharedPrefs.getInt(TrackpadConstants.KEY_LAST_DISPLAY_ID, -1)
            initialIndex = displays.indexOfFirst { it.displayId == savedId }
        }
        if (initialIndex == -1) {
            initialIndex = displays.indexOfFirst { it.displayId != Display.DEFAULT_DISPLAY }
        }
        if (initialIndex != -1) spinner.setSelection(initialIndex)

        // Setup Sensitivity
        seekBar.progress = (detector.sensitivity * 50f - 10f).toInt().coerceIn(0, 100)

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(dialogView)
            .create()

        // Make the dialog wider (set before show to avoid flicker)
        dialog.window?.let { dialogWindow ->
            val params = dialogWindow.attributes
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            dialogWindow.attributes = params
        }

        // Guard against spinner auto-firing onItemSelected on attach
        var userInitiatedSpinner = false
        spinner.post { userInitiatedSpinner = true }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!userInitiatedSpinner) return
                val selectedDisplay = displays[position]
                TrackpadEventBus.send(TrackpadEvent.SelectDisplay(selectedDisplay.displayId))
                sharedPrefs.edit { putInt(TrackpadConstants.KEY_LAST_DISPLAY_ID, selectedDisplay.displayId) }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                detector.sensitivity = (progress + 10) / 50.0f
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                sharedPrefs.edit { putFloat(TrackpadConstants.KEY_SENSITIVITY, detector.sensitivity) }
            }
        })

        dialogView.findViewById<View>(R.id.btn_close_settings).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * Live typing through a hidden EditText. The field always holds [KEYBOARD_FILLER] with the caret
     * at the end, so a backspace always has something to delete and shows up as a removal. Every
     * change is forwarded to the service and the field is then reset to the filler.
     */
    private fun setupKeyboard() {
        val hiddenEditText = findViewById<EditText>(R.id.hidden_edit_text)
        val btnKeyboard = findViewById<ImageButton>(R.id.btn_keyboard)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        var resetting = false
        fun resetField(editable: Editable) {
            resetting = true
            try {
                editable.replace(0, editable.length, KEYBOARD_FILLER)
                Selection.setSelection(editable, editable.length)
            } finally {
                resetting = false
            }
        }

        hiddenEditText.text?.let { resetField(it) }

        hiddenEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (resetting || s == null) return
                repeat(before) { TrackpadEventBus.send(TrackpadEvent.Backspace) }
                if (count > 0 && start >= 0 && start + count <= s.length) {
                    val inserted = s.subSequence(start, start + count).toString()
                    sendTypedText(inserted)
                }
            }

            override fun afterTextChanged(s: Editable?) {
                if (resetting || s == null) return
                if (s.toString() != KEYBOARD_FILLER || Selection.getSelectionStart(s) != s.length) {
                    resetField(s)
                }
            }
        })

        btnKeyboard.setOnClickListener {
            hiddenEditText.requestFocus()
            imm.showSoftInput(hiddenEditText, 0)
        }

        hiddenEditText.setOnEditorActionListener { _, _, event ->
            // Hardware Enter produces a down and an up event; only act on the down.
            if (event == null || event.action == KeyEvent.ACTION_DOWN) {
                TrackpadEventBus.send(TrackpadEvent.Enter)
            }
            true
        }
    }

    private fun sendTypedText(text: String) {
        val lines = text.split('\n')
        lines.forEachIndexed { index, line ->
            if (index > 0) TrackpadEventBus.send(TrackpadEvent.Enter)
            if (line.isNotEmpty()) TrackpadEventBus.send(TrackpadEvent.Text(line))
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }

    private fun updateAccessibilityStatus() {
        val statusText = findViewById<TextView>(R.id.status_text)
        val accessibilityButton = findViewById<Button>(R.id.btn_accessibility)
        statusText.visibility = View.VISIBLE

        if (isAccessibilityServiceEnabled()) {
            statusText.setText(R.string.status_active)
            accessibilityButton.visibility = View.GONE
        } else {
            statusText.setText(R.string.status_accessibility_required)
            accessibilityButton.visibility = View.VISIBLE
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

    /** Centroid of the first two pointers in [event] (or the only pointer), skipping [skipIndex]. */
    private fun centroid(event: MotionEvent, skipIndex: Int = -1): FloatArray {
        var sumX = 0f
        var sumY = 0f
        var used = 0
        for (i in 0 until event.pointerCount) {
            if (i == skipIndex) continue
            sumX += event.getX(i)
            sumY += event.getY(i)
            used++
            if (used == 2) break
        }
        if (used == 0) return floatArrayOf(0f, 0f)
        return floatArrayOf(sumX / used, sumY / used)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                detector.onDown(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val c = centroid(event)
                detector.onPointerDown(event.pointerCount, c[0], c[1])
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val c = centroid(event)
                detector.onMove(c[0], c[1])
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val c = centroid(event, skipIndex = event.actionIndex)
                detector.onPointerUp(event.pointerCount - 1, c[0], c[1])
                return true
            }

            MotionEvent.ACTION_UP -> {
                detector.onUp()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                detector.onCancel()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onPause() {
        super.onPause()
        // Release any held virtual finger and the pending long-press timer.
        detector.onCancel()
    }

    private companion object {
        const val KEYBOARD_FILLER = "        "
    }
}
