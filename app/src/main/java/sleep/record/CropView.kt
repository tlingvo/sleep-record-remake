package sleep.record

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

internal class CropView(context: Context, private val bitmap: Bitmap, private val palette: Palette) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val frame = RectF()
    private var x = 0f
    private var y = 0f
    private var scale = 1f
    private var minScale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val updated = (scale * detector.scaleFactor).coerceIn(minScale, minScale * 8)
            val factor = updated / scale
            x = detector.focusX + (x - detector.focusX) * factor
            y = detector.focusY + (y - detector.focusY) * factor
            scale = updated
            clamp()
            invalidate()
            return true
        }
    })
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val side = minOf(w, h) * .84f
        frame.set((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2)
        minScale = maxOf(side / bitmap.width, side / bitmap.height)
        scale = minScale
        x = (w - bitmap.width * scale) / 2
        y = (h - bitmap.height * scale) / 2
    }
    private fun clamp() {
        x = x.coerceIn(minOf(frame.right - bitmap.width * scale, frame.left), frame.left)
        y = y.coerceIn(minOf(frame.bottom - bitmap.height * scale, frame.top), frame.top)
    }
    private fun destination() = RectF(x, y, x + bitmap.width * scale, y + bitmap.height * scale)
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.bg)
        canvas.drawBitmap(bitmap, null, destination(), paint)
        paint.color = 0x99000000.toInt(); paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, width.toFloat(), frame.top, paint)
        canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), paint)
        canvas.drawRect(0f, frame.top, frame.left, frame.bottom, paint)
        canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, paint)
        paint.color = palette.accent; paint.style = Paint.Style.STROKE; paint.strokeWidth = context.dp(2).toFloat()
        canvas.drawRect(frame, paint)
        paint.style = Paint.Style.FILL
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_MOVE -> if (!detector.isInProgress && event.pointerCount == 1) {
                x += event.x - lastX; y += event.y - lastY; clamp(); invalidate()
            }
            MotionEvent.ACTION_UP -> { parent.requestDisallowInterceptTouchEvent(false); performClick() }
            MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
        }
        val pointer = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.actionIndex == 0) 1 else 0
        lastX = event.getX(pointer); lastY = event.getY(pointer)
        return true
    }
    override fun performClick() = super.performClick()
    fun output(): Bitmap {
        require(frame.width() > 0) { "裁剪区尚未就绪" }
        val out = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.scale(128 / frame.width(), 128 / frame.height())
        canvas.translate(-frame.left, -frame.top)
        canvas.drawBitmap(bitmap, null, destination(), paint)
        return out
    }
}
