package org.example.voicepitchlooper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/** One physical track with separate lower and upper pitch-ratio handles.
 * The two halves use different scales so 0.3..1.0 is not squeezed into a few pixels.
 * High end spans 1.0..10.0; pitch/timbre quality may worsen at the extremes.
 */
class PitchRangeSlider(context: Context) : View(context) {
    var lower = 0.5; private set
    var upper = 3.5; private set
    var onRangeChanged: ((Double, Double) -> Unit)? = null
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colors = UiTheme.palette(context)
    private val density = resources.displayMetrics.density
    private var dragging = 0 // 1 = lower, 2 = upper
    private fun dp(n: Float) = n * density
    private val centerFraction = 0.40f
    private fun leftX() = paddingLeft + dp(24f)
    private fun rightX() = width - paddingRight - dp(24f)
    private fun midX() = leftX() + (rightX() - leftX()) * centerFraction
    private fun lowX() = leftX() + (midX() - leftX()) * ((lower - 0.3) / 0.7).toFloat()
    private fun highX() = midX() + (rightX() - midX()) * ((upper - 1.0) / 9.0).toFloat()
    private fun levelY() = dp(37f)

    init {
        contentDescription = UiLanguage.text(context, "変換倍率の範囲を設定。左つまみは低音側、右つまみは高音側")
        isClickable = true
        setPadding(0, 0, 0, 0)
    }

    fun setRange(minimum: Double, maximum: Double) {
        lower = PitchRange.lowerStep(minimum)
        upper = PitchRange.upperStep(maximum)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(dp(85f).toInt(), heightMeasureSpec))
    }

    private fun drawLine(canvas: Canvas, x0: Float, x1: Float, y: Float,
                         color: Int, thickness: Float) {
        ink.color = color
        ink.strokeWidth = dp(thickness)
        ink.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x0, y, x1, y, ink)
    }

    private fun txt(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int) {
        ink.color = color
        ink.textSize = dp(size)
        ink.textAlign = Paint.Align.CENTER
        ink.style = Paint.Style.FILL
        canvas.drawText(text, x, y, ink)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = leftX(); val right = rightX(); val mid = midX(); val y = levelY()
        drawLine(canvas, left, right, y, colors.track, 6f)
        drawLine(canvas, lowX(), highX(), y, colors.accent, 6f)
        drawLine(canvas, mid, mid, y - dp(11f), colors.muted, 2f)
        txt(canvas, "0.3", left, y + dp(29f), 11f, colors.muted)
        txt(canvas, "1.0", mid, y + dp(29f), 11f, colors.muted)
        txt(canvas, "10.0", right, y + dp(29f), 11f, colors.muted)
        for ((cx, color) in listOf(lowX() to colors.low,
                                  highX() to colors.high)) {
            ink.color = colors.handleOutline
            canvas.drawCircle(cx, y, dp(13f), ink)
            ink.color = color
            canvas.drawCircle(cx, y, dp(10f), ink)
        }
        txt(canvas, UiLanguage.text(context, "低音側"), (left + mid) * 0.5f, dp(14f), 12f, colors.low)
        txt(canvas, UiLanguage.text(context, "高音側"), (mid + right) * 0.5f, dp(14f), 12f, colors.high)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                dragging = if (abs(lowX() - highX()) < dp(4f)) {
                    if (x < midX()) 1 else 2
                } else if (abs(x - lowX()) <= abs(x - highX())) 1 else 2
                parent?.requestDisallowInterceptTouchEvent(true)
                updateAt(x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging != 0) updateAt(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging != 0) updateAt(event.x)
                dragging = 0
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = 0
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateAt(x: Float) {
        val span = if (dragging == 1) midX() - leftX() else rightX() - midX()
        if (span <= 0) return
        val fraction = if (dragging == 1) ((x - leftX()) / span).coerceIn(0f, 1f)
                       else ((x - midX()) / span).coerceIn(0f, 1f)
        val low = if (dragging == 1) PitchRange.lowerStep(0.3 + fraction * 0.7) else lower
        val high = if (dragging == 2) PitchRange.upperStep(1.0 + fraction * 9.0) else upper
        if (low != lower || high != upper) {
            lower = low; upper = high
            invalidate()
            onRangeChanged?.invoke(lower, upper)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
