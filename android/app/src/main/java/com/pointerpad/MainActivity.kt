package com.pointerpad

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var sender: UdpSender
    private var connected = false
    private var imageReceiver: ImageReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sender = UdpSender()

        val ipLayout = findViewById<TextInputLayout>(R.id.ipLayout)
        val tokenLayout = findViewById<TextInputLayout>(R.id.tokenLayout)
        val ipInput = findViewById<TextInputEditText>(R.id.ipInput)
        val tokenInput = findViewById<TextInputEditText>(R.id.tokenInput)
        val connectButton = findViewById<MaterialButton>(R.id.connectButton)
        val testButton = findViewById<MaterialButton>(R.id.testButton)
        val statusDot = findViewById<View>(R.id.statusDot)
        val statusText = findViewById<TextView>(R.id.statusText)
        val modeToggle = findViewById<SwitchCompat>(R.id.modeToggle)
        val eraserToggle = findViewById<SwitchCompat>(R.id.eraserToggle)
        val imageMoveToggle = findViewById<SwitchCompat>(R.id.imageMoveToggle)
        val mirrorToggle = findViewById<SwitchCompat>(R.id.mirrorToggle)
        val stylusToggle = findViewById<SwitchCompat>(R.id.stylusToggle)
        val pointerPad = findViewById<PointerPadView>(R.id.pointerPad)
        val drawingPad = findViewById<DrawingPadView>(R.id.drawingPad)
        val mouseToolsSection = findViewById<View>(R.id.mouseToolsSection)
        val drawingToolsSection = findViewById<View>(R.id.drawingToolsSection)
        val helpText = findViewById<TextView>(R.id.helpText)

        val swatchBlack = findViewById<MaterialCardView>(R.id.swatchBlack)
        val swatchRed = findViewById<MaterialCardView>(R.id.swatchRed)
        val swatchBlue = findViewById<MaterialCardView>(R.id.swatchBlue)
        val swatchGreen = findViewById<MaterialCardView>(R.id.swatchGreen)
        val undoButton = findViewById<MaterialButton>(R.id.undoButton)
        val clearButton = findViewById<MaterialButton>(R.id.clearButton)
        val leftClickButton = findViewById<MaterialButton>(R.id.leftClickButton)
        val rightClickButton = findViewById<MaterialButton>(R.id.rightClickButton)

        pointerPad.setSender(sender)
        drawingPad.setSender(sender)

        imageReceiver = ImageReceiver(50506) { packet ->
            if (sender.token.isNotEmpty() && packet.token != sender.token) {
                return@ImageReceiver
            }
            val bitmap = BitmapFactory.decodeByteArray(packet.bytes, 0, packet.bytes.size) ?: return@ImageReceiver
            runOnUiThread {
                drawingPad.addImage(packet.id, bitmap, packet.x, packet.y, packet.width, packet.height)
            }
        }
        imageReceiver?.start()

        val prefs = getSharedPreferences("pointerpad", MODE_PRIVATE)
        ipInput.setText(prefs.getString("host", ""))
        tokenInput.setText(prefs.getString("token", ""))
        modeToggle.isChecked = prefs.getBoolean("draw_mode", false)
        stylusToggle.isChecked = prefs.getBoolean("stylus_drag", true)
        pointerPad.setStylusDragEnabled(stylusToggle.isChecked)
        eraserToggle.isChecked = prefs.getBoolean("eraser_mode", false)
        mirrorToggle.isChecked = prefs.getBoolean("mirror_mouse", false)
        drawingPad.setEraserEnabled(eraserToggle.isChecked)
        drawingPad.setMirrorMouseEnabled(mirrorToggle.isChecked)
        imageMoveToggle.isChecked = prefs.getBoolean("image_move", false)
        drawingPad.setImageMoveEnabled(imageMoveToggle.isChecked)

        val defaultColor = ContextCompat.getColor(this, R.color.ink_black)
        val savedColor = prefs.getInt("ink_color", defaultColor)
        setInkColor(savedColor, drawingPad, swatchBlack, swatchRed, swatchBlue, swatchGreen, prefs)

        applyMode(modeToggle.isChecked, pointerPad, drawingPad, mouseToolsSection, drawingToolsSection, helpText)
        updateStatus(statusDot, statusText, Status.IDLE, null)
        setConnected(false, connectButton, testButton, eraserToggle, imageMoveToggle, mirrorToggle)

        connectButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val host = ipInput.text?.toString()?.trim().orEmpty()
            val token = tokenInput.text?.toString().orEmpty()

            if (connected) {
                sender.updateTarget("", 50505)
                setConnected(false, connectButton, testButton, eraserToggle, imageMoveToggle, mirrorToggle)
                updateStatus(statusDot, statusText, Status.IDLE, null)
                return@setOnClickListener
            }

            if (!isValidIp(host)) {
                ipLayout.error = "Invalid IP address"
                updateStatus(statusDot, statusText, Status.ERROR, "Invalid IP")
                return@setOnClickListener
            }
            ipLayout.error = null

            sender.updateTarget(host, 50505)
            sender.token = token
            pointerPad.setToken(token)
            drawingPad.setToken(token)

            prefs.edit()
                .putString("host", host)
                .putString("token", token)
                .apply()

            setConnected(true, connectButton, testButton, eraserToggle, imageMoveToggle, mirrorToggle)
            updateStatus(statusDot, statusText, Status.READY, host)
        }

        testButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val obj = JSONObject()
            obj.put("type", "test")
            obj.put("token", sender.token)
            sender.send(obj)
            updateStatus(statusDot, statusText, Status.TEST_SENT, null)
        }

        modeToggle.setOnCheckedChangeListener { _, isChecked ->
            applyMode(isChecked, pointerPad, drawingPad, mouseToolsSection, drawingToolsSection, helpText)
            prefs.edit().putBoolean("draw_mode", isChecked).apply()
        }

        stylusToggle.setOnCheckedChangeListener { _, isChecked ->
            pointerPad.setStylusDragEnabled(isChecked)
            prefs.edit().putBoolean("stylus_drag", isChecked).apply()
        }

        eraserToggle.setOnCheckedChangeListener { _, isChecked ->
            drawingPad.setEraserEnabled(isChecked)
            prefs.edit().putBoolean("eraser_mode", isChecked).apply()
        }

        imageMoveToggle.setOnCheckedChangeListener { _, isChecked ->
            drawingPad.setImageMoveEnabled(isChecked)
            prefs.edit().putBoolean("image_move", isChecked).apply()
        }

        mirrorToggle.setOnCheckedChangeListener { _, isChecked ->
            drawingPad.setMirrorMouseEnabled(isChecked)
            prefs.edit().putBoolean("mirror_mouse", isChecked).apply()
        }

        swatchBlack.setOnClickListener { setInkColor(ContextCompat.getColor(this, R.color.ink_black), drawingPad, swatchBlack, swatchRed, swatchBlue, swatchGreen, prefs) }
        swatchRed.setOnClickListener { setInkColor(ContextCompat.getColor(this, R.color.ink_red), drawingPad, swatchBlack, swatchRed, swatchBlue, swatchGreen, prefs) }
        swatchBlue.setOnClickListener { setInkColor(ContextCompat.getColor(this, R.color.ink_blue), drawingPad, swatchBlack, swatchRed, swatchBlue, swatchGreen, prefs) }
        swatchGreen.setOnClickListener { setInkColor(ContextCompat.getColor(this, R.color.ink_green), drawingPad, swatchBlack, swatchRed, swatchBlue, swatchGreen, prefs) }

        leftClickButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            pointerPad.sendClick("left")
        }
        rightClickButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            pointerPad.sendClick("right")
        }

        undoButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            drawingPad.undo()
        }
        clearButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            drawingPad.clear()
        }
    }

    override fun onDestroy() {
        imageReceiver?.stop()
        sender.close()
        super.onDestroy()
    }

    private fun applyMode(
        drawMode: Boolean,
        pointerPad: PointerPadView,
        drawingPad: DrawingPadView,
        mouseToolsSection: View,
        drawingToolsSection: View,
        helpText: TextView
    ) {
        if (drawMode) {
            pointerPad.visibility = View.GONE
            drawingPad.visibility = View.VISIBLE
            mouseToolsSection.visibility = View.GONE
            drawingToolsSection.visibility = View.VISIBLE
            helpText.text = "Draw: stylus. Pan: one finger. Zoom: two fingers. Move image: toggle."
        } else {
            pointerPad.visibility = View.VISIBLE
            drawingPad.visibility = View.GONE
            mouseToolsSection.visibility = View.VISIBLE
            drawingToolsSection.visibility = View.GONE
            helpText.text = "Mouse: tap to click. Two fingers to scroll."
        }
    }

    private fun setConnected(
        isConnected: Boolean,
        connectButton: MaterialButton,
        testButton: MaterialButton,
        eraserToggle: SwitchCompat,
        imageMoveToggle: SwitchCompat,
        mirrorToggle: SwitchCompat
    ) {
        connected = isConnected
        connectButton.text = if (isConnected) "Disconnect" else "Connect"
        testButton.isEnabled = isConnected
        eraserToggle.isEnabled = isConnected
        imageMoveToggle.isEnabled = isConnected
        mirrorToggle.isEnabled = isConnected
    }

    private fun setInkColor(
        color: Int,
        drawingPad: DrawingPadView,
        swatchBlack: MaterialCardView,
        swatchRed: MaterialCardView,
        swatchBlue: MaterialCardView,
        swatchGreen: MaterialCardView,
        prefs: SharedPreferences
    ) {
        drawingPad.setColor(color)

        fun updateStroke(swatch: MaterialCardView, selected: Boolean) {
            swatch.strokeWidth = if (selected) 4 else 0
            swatch.strokeColor = if (selected) Color.WHITE else Color.TRANSPARENT
        }

        updateStroke(swatchBlack, color == ContextCompat.getColor(this, R.color.ink_black))
        updateStroke(swatchRed, color == ContextCompat.getColor(this, R.color.ink_red))
        updateStroke(swatchBlue, color == ContextCompat.getColor(this, R.color.ink_blue))
        updateStroke(swatchGreen, color == ContextCompat.getColor(this, R.color.ink_green))

        prefs.edit().putInt("ink_color", color).apply()
    }

    private fun updateStatus(dot: View, text: TextView, status: Status, host: String?) {
        val (label, color) = when (status) {
            Status.IDLE -> "Not connected" to 0xFFB0B0B0.toInt()
            Status.READY -> "Ready: $host" to 0xFF2E7D32.toInt()
            Status.TEST_SENT -> "Test signal sent" to 0xFFF9A825.toInt()
            Status.ERROR -> (host ?: "Error") to 0xFFC62828.toInt()
        }
        text.text = label
        val bg = dot.background
        if (bg is GradientDrawable) {
            bg.setColor(color)
        }
    }

    private fun isValidIp(value: String): Boolean {
        val parts = value.split(".")
        if (parts.size != 4) return false
        for (p in parts) {
            val n = p.toIntOrNull() ?: return false
            if (n < 0 || n > 255) return false
        }
        return true
    }

    private enum class Status {
        IDLE,
        READY,
        TEST_SENT,
        ERROR
    }
}
