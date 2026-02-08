package com.pointerpad

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

class PointerPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val tapTimeout = ViewConfiguration.getTapTimeout()
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout()

    private var sender: UdpSender? = null
    private var token: String = ""
    private var stylusDragEnabled = true
    private var stylusActive = false
    private var hovering = false
    private var lastHoverX = 0f
    private var lastHoverY = 0f

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var moved = false
    private var dragging = false

    private var twoFinger = false
    private var twoFingerMoved = false
    private var twoFingerStart = 0L
    private var twoFingerLastY = 0f
    private var scrollRemainder = 0f
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var sendScheduled = false

    private val handler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (!moved && !twoFinger) {
            startDrag()
        }
    }
    private val sendRunnable = Runnable {
        sendScheduled = false
        val dx = pendingDx
        val dy = pendingDy
        pendingDx = 0f
        pendingDy = 0f
        if (dx != 0f || dy != 0f) {
            sendMove(dx, dy)
        }
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (!stylusDragEnabled) return super.onHoverEvent(event)
        if (event.pointerCount < 1) return super.onHoverEvent(event)
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) {
            return super.onHoverEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> {
                hovering = true
                lastHoverX = event.x
                lastHoverY = event.y
                return true
            }
            MotionEvent.ACTION_HOVER_MOVE -> {
                if (!hovering) {
                    hovering = true
                    lastHoverX = event.x
                    lastHoverY = event.y
                    return true
                }
                val dx = event.x - lastHoverX
                val dy = event.y - lastHoverY
                if (dx != 0f || dy != 0f) {
                    enqueueMove(dx, dy)
                    lastHoverX = event.x
                    lastHoverY = event.y
                }
                return true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                hovering = false
                return true
            }
        }
        return super.onHoverEvent(event)
    }

    fun setSender(sender: UdpSender) {
        this.sender = sender
    }

    fun setToken(token: String) {
        this.token = token
    }

    fun setStylusDragEnabled(enabled: Boolean) {
        stylusDragEnabled = enabled
    }

    fun sendClick(button: String) {
        sendButton(button, "down")
        sendButton(button, "up")
    }

    private fun startDrag() {
        dragging = true
        sendButton("left", "down")
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
                moved = false
                dragging = false
                stylusActive = isStylus(event, 0) && stylusDragEnabled
                hovering = false
                downTime = event.eventTime
                twoFinger = false
                if (stylusActive) {
                    dragging = true
                    sendButton("left", "down")
                } else {
                    handler.postDelayed(longPressRunnable, longPressTimeout.toLong())
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (stylusActive) {
                    return true
                }
                if (event.pointerCount == 2) {
                    twoFinger = true
                    twoFingerMoved = false
                    twoFingerStart = event.eventTime
                    twoFingerLastY = averageY(event)
                    scrollRemainder = 0f
                    handler.removeCallbacks(longPressRunnable)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (twoFinger && event.pointerCount >= 2) {
                    val avgY = averageY(event)
                    val dy = avgY - twoFingerLastY
                    if (abs(dy) > 0.5f) {
                        twoFingerMoved = true
                        handleScroll(dy)
                        twoFingerLastY = avgY
                    }
                } else {
                    val idx = event.findPointerIndex(activePointerId)
                    if (idx >= 0) {
                        val x = event.getX(idx)
                        val y = event.getY(idx)
                        val dx = x - lastX
                        val dy = y - lastY
                        if (dx != 0f || dy != 0f) {
                            enqueueMove(dx, dy)
                            lastX = x
                            lastY = y
                        }
                        if (!moved && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                            moved = true
                            handler.removeCallbacks(longPressRunnable)
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (stylusActive) {
                    return true
                }
                if (twoFinger && event.pointerCount == 2) {
                    val duration = event.eventTime - twoFingerStart
                    if (!twoFingerMoved && duration < tapTimeout) {
                        sendClick("right")
                    }
                    twoFinger = false
                    twoFingerMoved = false
                    resetSinglePointer(event)
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                if (stylusActive) {
                    sendButton("left", "up")
                } else if (twoFinger) {
                    val duration = event.eventTime - twoFingerStart
                    if (!twoFingerMoved && duration < tapTimeout) {
                        sendClick("right")
                    }
                } else if (dragging) {
                    sendButton("left", "up")
                } else {
                    val duration = event.eventTime - downTime
                    if (!moved && duration < tapTimeout) {
                        sendClick("left")
                    }
                }
                resetState()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (stylusActive) {
                    sendButton("left", "up")
                } else if (dragging) {
                    sendButton("left", "up")
                }
                resetState()
            }
        }
        return true
    }

    private fun resetSinglePointer(event: MotionEvent) {
        val remainingIndex = if (event.actionIndex == 0) 1 else 0
        if (remainingIndex < event.pointerCount) {
            activePointerId = event.getPointerId(remainingIndex)
            lastX = event.getX(remainingIndex)
            lastY = event.getY(remainingIndex)
            moved = false
            downTime = event.eventTime
            handler.postDelayed(longPressRunnable, longPressTimeout.toLong())
        }
    }

    private fun resetState() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        moved = false
        dragging = false
        twoFinger = false
        twoFingerMoved = false
        pendingDx = 0f
        pendingDy = 0f
        sendScheduled = false
        stylusActive = false
        hovering = false
    }

    private fun isStylus(event: MotionEvent, index: Int): Boolean {
        return event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS
    }

    private fun averageY(event: MotionEvent): Float {
        val y0 = event.getY(0)
        val y1 = event.getY(1)
        return (y0 + y1) / 2f
    }

    private fun handleScroll(dyPixels: Float) {
        // Convert pixels to scroll lines with accumulation.
        scrollRemainder += dyPixels / 12f
        val lines = scrollRemainder.toInt()
        if (lines != 0) {
            scrollRemainder -= lines
            val obj = JSONObject()
            obj.put("type", "scroll")
            obj.put("dx", 0)
            obj.put("dy", lines)
            obj.put("token", token)
            sender?.send(obj)
        }
    }

    private fun sendMove(dx: Float, dy: Float) {
        val obj = JSONObject()
        obj.put("type", "move")
        obj.put("dx", dx)
        obj.put("dy", dy)
        obj.put("token", token)
        sender?.send(obj)
    }

    private fun sendButton(button: String, state: String) {
        val obj = JSONObject()
        obj.put("type", "button")
        obj.put("button", button)
        obj.put("state", state)
        obj.put("token", token)
        sender?.send(obj)
    }

    private fun enqueueMove(dx: Float, dy: Float) {
        pendingDx += dx
        pendingDy += dy
        if (!sendScheduled) {
            sendScheduled = true
            postOnAnimation(sendRunnable)
        }
    }
}
