package com.pointerpad

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class DrawingPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Stroke(
        val id: Long,
        val color: Int,
        val width: Float,
        val points: MutableList<PointF>
    )

    private data class ImageLayer(
        val id: String,
        val bitmap: Bitmap,
        var x: Float,
        var y: Float,
        val width: Float,
        val height: Float
    )

    private var sender: UdpSender? = null
    private var token: String = ""
    private var currentColor: Int = Color.BLACK
    private var strokeWidthPx: Float = 6f
    private var mirrorMouse = false
    private var eraserEnabled = false
    private var eraserRadiusPx: Float = 18f
    private var imageMoveEnabled = false

    private val strokes = mutableListOf<Stroke>()
    private val images = mutableListOf<ImageLayer>()
    private var activeStroke: Stroke? = null
    private var activeImage: ImageLayer? = null
    private var imageGrabDx = 0f
    private var imageGrabDy = 0f
    private var lastMirrorX = 0f
    private var lastMirrorY = 0f
    private var mirrorDown = false

    private var viewOffsetX = 0f
    private var viewOffsetY = 0f
    private var viewScale = 1f
    private var isPanning = false
    private var lastSpan = 0f
    private var lastPanX = 0f
    private var lastPanY = 0f

    private val minScale = 0.2f
    private val maxScale = 6f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ECECEC")
        strokeWidth = 1f
    }
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0B0B0")
        textSize = 36f
        textAlign = Paint.Align.CENTER
    }
    private val baseGridSpacing = 120f

    fun setSender(sender: UdpSender) {
        this.sender = sender
    }

    fun setToken(token: String) {
        this.token = token
    }

    fun setColor(color: Int) {
        currentColor = color
    }

    fun setMirrorMouseEnabled(enabled: Boolean) {
        mirrorMouse = enabled
    }

    fun setEraserEnabled(enabled: Boolean) {
        eraserEnabled = enabled
    }

    fun setImageMoveEnabled(enabled: Boolean) {
        imageMoveEnabled = enabled
    }

    fun addImage(id: String, bitmap: Bitmap, x: Float, y: Float, width: Float, height: Float) {
        images.add(ImageLayer(id, bitmap, x, y, width, height))
        invalidate()
    }

    fun moveImage(id: String, x: Float, y: Float) {
        val img = images.firstOrNull { it.id == id } ?: return
        img.x = x
        img.y = y
        invalidate()
    }

    fun undo() {
        if (strokes.isNotEmpty()) {
            strokes.removeLast()
            invalidate()
            sendSimple("draw_undo")
        }
    }

    fun clear() {
        strokes.clear()
        activeStroke = null
        invalidate()
        sendSimple("draw_clear")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sendView()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGrid(canvas)
        if (strokes.isEmpty() && images.isEmpty()) {
            canvas.drawText("Draw here", width / 2f, height / 2f, placeholderPaint)
        }

        for (image in images) {
            val topLeft = worldToScreen(PointF(image.x, image.y))
            val w = image.width * viewScale
            val h = image.height * viewScale
            val right = topLeft.x + w
            val bottom = topLeft.y + h
            canvas.drawBitmap(image.bitmap, null, android.graphics.RectF(topLeft.x, topLeft.y, right, bottom), null)
        }

        for (stroke in strokes) {
            if (stroke.points.size < 2) continue
            paint.color = stroke.color
            paint.strokeWidth = max(1f, stroke.width * viewScale)
            for (i in 1 until stroke.points.size) {
                val p0 = worldToScreen(stroke.points[i - 1])
                val p1 = worldToScreen(stroke.points[i])
                canvas.drawLine(p0.x, p0.y, p1.x, p1.y, paint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount >= 2) {
            handlePanZoom(event)
            return true
        }

        val x = event.x
        val y = event.y

        if (imageMoveEnabled) {
            if (handleImageMove(event, x, y)) {
                return true
            }
        }

        val tool = event.getToolType(0)
        if (tool != MotionEvent.TOOL_TYPE_STYLUS) {
            handleSinglePan(event, x, y)
            return true
        }

        val world = screenToWorld(x, y)
        val erasing = isErasing(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sendView()
                if (erasing) {
                    eraseAt(world)
                } else {
                    val id = System.nanoTime()
                    val stroke = Stroke(id, currentColor, strokeWidthPx, mutableListOf(PointF(world.x, world.y)))
                    strokes.add(stroke)
                    activeStroke = stroke
                    invalidate()
                    sendDraw("draw_begin", stroke.id, world.x, world.y, stroke.color, stroke.width)

                    if (mirrorMouse) {
                        mirrorDown = true
                        lastMirrorX = x
                        lastMirrorY = y
                        sendButton("left", "down")
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (erasing) {
                    if (activeStroke != null) {
                        activeStroke = null
                        if (mirrorMouse && mirrorDown) {
                            sendButton("left", "up")
                            mirrorDown = false
                        }
                    }
                    val history = event.historySize
                    for (i in 0 until history) {
                        val hx = event.getHistoricalX(0, i)
                        val hy = event.getHistoricalY(0, i)
                        eraseAt(screenToWorld(hx, hy))
                    }
                    eraseAt(world)
                } else {
                    val stroke = activeStroke ?: return true
                    val history = event.historySize
                    for (i in 0 until history) {
                        val hx = event.getHistoricalX(0, i)
                        val hy = event.getHistoricalY(0, i)
                        addPoint(stroke, screenToWorld(hx, hy))
                    }
                    addPoint(stroke, world)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val stroke = activeStroke
                if (!erasing && stroke != null) {
                    addPoint(stroke, world)
                    sendDraw("draw_end", stroke.id, world.x, world.y, stroke.color, stroke.width)
                }
                activeStroke = null
                if (mirrorMouse && mirrorDown) {
                    sendButton("left", "up")
                    mirrorDown = false
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleImageMove(event: MotionEvent, x: Float, y: Float): Boolean {
        val world = screenToWorld(x, y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = hitTestImage(world)
                if (hit != null) {
                    activeImage = hit
                    imageGrabDx = world.x - hit.x
                    imageGrabDy = world.y - hit.y
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val img = activeImage ?: return false
                img.x = world.x - imageGrabDx
                img.y = world.y - imageGrabDy
                sendImageMove(img)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeImage = null
            }
        }
        return false
    }

    private fun hitTestImage(world: PointF): ImageLayer? {
        for (i in images.indices.reversed()) {
            val img = images[i]
            if (world.x >= img.x && world.x <= img.x + img.width &&
                world.y >= img.y && world.y <= img.y + img.height
            ) {
                return img
            }
        }
        return null
    }

    private fun handleSinglePan(event: MotionEvent, x: Float, y: Float) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isPanning = true
                lastPanX = x
                lastPanY = y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isPanning) {
                    isPanning = true
                    lastPanX = x
                    lastPanY = y
                    return
                }
                val dx = x - lastPanX
                val dy = y - lastPanY
                if (dx != 0f || dy != 0f) {
                    viewOffsetX -= dx / viewScale
                    viewOffsetY -= dy / viewScale
                    lastPanX = x
                    lastPanY = y
                    sendView()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPanning = false
            }
        }
    }

    private fun handlePanZoom(event: MotionEvent) {
        if (event.pointerCount < 2) {
            isPanning = false
            return
        }

        if (activeStroke != null) {
            activeStroke = null
            if (mirrorMouse && mirrorDown) {
                sendButton("left", "up")
                mirrorDown = false
            }
        }

        val x0 = event.getX(0)
        val y0 = event.getY(0)
        val x1 = event.getX(1)
        val y1 = event.getY(1)
        val midX = (x0 + x1) / 2f
        val midY = (y0 + y1) / 2f
        val span = max(1f, hypot(x1 - x0, y1 - y0))

        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_DOWN -> {
                isPanning = true
                lastSpan = span
                return
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isPanning) {
                    isPanning = true
                    lastSpan = span
                    return
                }
                val worldMid = screenToWorld(midX, midY)
                val scaleFactor = span / lastSpan
                val newScale = clamp(viewScale * scaleFactor, minScale, maxScale)
                viewScale = newScale
                viewOffsetX = worldMid.x - midX / viewScale
                viewOffsetY = worldMid.y - midY / viewScale
                lastSpan = span
                sendView()
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPanning = false
            }
        }
    }

    private fun drawGrid(canvas: Canvas) {
        if (width == 0 || height == 0) return
        var spacing = baseGridSpacing
        while (spacing * viewScale < 40f) {
            spacing *= 2f
        }
        val worldLeft = viewOffsetX
        val worldTop = viewOffsetY
        val worldRight = viewOffsetX + width / viewScale
        val worldBottom = viewOffsetY + height / viewScale

        var x = kotlin.math.floor(worldLeft / spacing) * spacing
        while (x <= worldRight) {
            val sx = (x - viewOffsetX) * viewScale
            canvas.drawLine(sx, 0f, sx, height.toFloat(), gridPaint)
            x += spacing
        }

        var y = kotlin.math.floor(worldTop / spacing) * spacing
        while (y <= worldBottom) {
            val sy = (y - viewOffsetY) * viewScale
            canvas.drawLine(0f, sy, width.toFloat(), sy, gridPaint)
            y += spacing
        }
    }

    private fun addPoint(stroke: Stroke, point: PointF) {
        val last = stroke.points.last()
        if (abs(last.x - point.x) < 0.5f && abs(last.y - point.y) < 0.5f) return
        stroke.points.add(PointF(point.x, point.y))
        sendDraw("draw_move", stroke.id, point.x, point.y, stroke.color, stroke.width)

        if (mirrorMouse && mirrorDown) {
            val screen = worldToScreen(point)
            val dx = screen.x - lastMirrorX
            val dy = screen.y - lastMirrorY
            if (dx != 0f || dy != 0f) {
                sendMove(dx, dy)
                lastMirrorX = screen.x
                lastMirrorY = screen.y
            }
        }
    }

    private fun eraseAt(point: PointF) {
        if (strokes.isEmpty()) return
        val radius = eraserRadiusPx / viewScale
        val radiusSq = radius * radius
        var changed = false
        val iterator = strokes.listIterator(strokes.size)
        while (iterator.hasPrevious()) {
            val stroke = iterator.previous()
            if (stroke.points.any { p ->
                    val dx = p.x - point.x
                    val dy = p.y - point.y
                    dx * dx + dy * dy <= radiusSq
                }) {
                iterator.remove()
                changed = true
            }
        }
        if (changed) {
            invalidate()
            sendErase(point.x, point.y, radius)
        }
    }

    private fun screenToWorld(x: Float, y: Float): PointF {
        return PointF(x / viewScale + viewOffsetX, y / viewScale + viewOffsetY)
    }

    private fun worldToScreen(point: PointF): PointF {
        return PointF((point.x - viewOffsetX) * viewScale, (point.y - viewOffsetY) * viewScale)
    }

    private fun sendView() {
        if (width <= 0 || height <= 0) return
        val obj = JSONObject()
        obj.put("type", "draw_view")
        obj.put("offset_x", viewOffsetX)
        obj.put("offset_y", viewOffsetY)
        obj.put("scale", viewScale)
        obj.put("view_w", width)
        obj.put("view_h", height)
        obj.put("token", token)
        sender?.send(obj)
    }

    private fun sendDraw(type: String, id: Long, x: Float, y: Float, color: Int, widthPx: Float) {
        val obj = JSONObject()
        obj.put("type", type)
        obj.put("id", id)
        obj.put("x", x)
        obj.put("y", y)
        obj.put("color", color)
        obj.put("width", widthPx)
        obj.put("token", token)
        sender?.send(obj)
    }

    private fun sendErase(x: Float, y: Float, radius: Float) {
        val obj = JSONObject()
        obj.put("type", "draw_erase")
        obj.put("x", x)
        obj.put("y", y)
        obj.put("radius", radius)
        obj.put("token", token)
        sender?.send(obj)
    }

    private fun sendImageMove(image: ImageLayer) {
        val obj = JSONObject()
        obj.put("type", "image_move")
        obj.put("id", image.id)
        obj.put("x", image.x)
        obj.put("y", image.y)
        obj.put("token", token)
        sender?.send(obj)
    }

    private fun sendSimple(type: String) {
        val obj = JSONObject()
        obj.put("type", type)
        obj.put("token", token)
        sender?.send(obj)
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

    private fun isErasing(event: MotionEvent): Boolean {
        val isStylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        val buttons = event.buttonState
        val stylusButton = (buttons and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 ||
            (buttons and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0
        return eraserEnabled || (isStylus && stylusButton)
    }

    private fun clamp(value: Float, minValue: Float, maxValue: Float): Float {
        return min(max(value, minValue), maxValue)
    }
}
