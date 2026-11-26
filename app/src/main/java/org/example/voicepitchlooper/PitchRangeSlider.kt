package org.example.voicepitchlooper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/** Restored compact two-thumb pitch-range track from app(6).zip.
 *  One physical track with separate lower and upper pitch-ratio handles.
 * The two halves use different scales so 0.3..1.0 is not squeezed into a few pixels.
 * High end spans 1.0..10.0; pitch/timbre quality may worsen at the extremes.
 */
class PitchRangeSlider(context: Context) : View(context) {
    var lower = 0.5; private set
    var upper = 3.5; private set
    var onRangeChanged: ((Double, Double) -> Unit)? = null
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val palette = UiTheme.palette(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val density = resources.displayMetrics.density
    // A touch is only eligible when it begins on one of the two thumb circles.
    // An intentional horizontal gesture is required before claiming the ScrollView touch stream.
    private var pendingThumb = 0 // 1 = lower, 2 = upper
    private var dragging = 0
    private var rejectedVertical = false
    private var downX = 0f
    private var downY = 0f
    private fun dp(n: Float) = n * density
    private val centerFraction = 0.40f
    private fun leftX() = paddingLeft + dp(24f)
    private fun rightX() = width - paddingRight - dp(24f)
    private fun midX() = leftX() + (rightX() - leftX()) * centerFraction
    private fun lowX() = leftX() + (midX() - leftX()) * ((lower - 0.3) / 0.7).toFloat()
    private fun highX() = midX() + (rightX() - midX()) * ((upper - 1.0) / 9.0).toFloat()
    private fun levelY() = dp(29f)

    init {
        contentDescription = context.getString(R.string.pitch_range_content_desc)
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
            resolveSize(dp(62f).toInt(), heightMeasureSpec))
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
        drawLine(canvas, left, right, y, palette.track, 6f)
        drawLine(canvas, lowX(), highX(), y, palette.accent, 6f)
        drawLine(canvas, mid, mid, y - dp(11f), palette.border, 2f)
        txt(canvas, "0.3", left, y + dp(23f), 11f, palette.muted)
        txt(canvas, "1.0", mid, y + dp(23f), 11f, palette.muted)
        txt(canvas, "10.0", right, y + dp(23f), 11f, palette.muted)
        for ((cx, color) in listOf(lowX() to palette.low,
                                  highX() to palette.high)) {
            ink.color = palette.handleOutline
            canvas.drawCircle(cx, y, dp(13f), ink)
            ink.color = color
            canvas.drawCircle(cx, y, dp(10f), ink)
        }
        txt(canvas, context.getString(R.string.pitch_lower_side), (left + mid) * 0.5f, dp(12f), 12f, palette.low)
        txt(canvas, context.getString(R.string.pitch_upper_side), (mid + right) * 0.5f, dp(12f), 12f, palette.high)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                // Outside the thumb hit box, leave scrolling to the parent.
                if (abs(y - levelY()) > dp(24f)) return false
                val lowDistance = abs(x - lowX())
                val highDistance = abs(x - highX())
                if (minOf(lowDistance, highDistance) > dp(24f)) return false
                pendingThumb = if (lowDistance == highDistance) {
                    if (x < midX()) 1 else 2
                } else if (lowDistance < highDistance) 1 else 2
                dragging = 0
                rejectedVertical = false
                downX = x
                downY = y
                // Do not block ScrollView here. Only lock touch after a horizontal drag.
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pendingThumb == 0) return false
                if (dragging == 0) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (rejectedVertical) return true
                    if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                        rejectedVertical = true
                        return true // parent ScrollView may intercept vertical movement
                    }
                    if (abs(dx) <= touchSlop || abs(dx) <= abs(dy) * 1.2f) return true
                    dragging = pendingThumb
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                updateAt(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                // Tapping the track or a thumb never changes the range.
                // Only a deliberate horizontal thumb drag adjusts the setting.
                if (dragging != 0) updateAt(event.x)
                val handled = pendingThumb != 0
                resetGesture()
                if (handled) performClick()
                return handled
            }
            MotionEvent.ACTION_CANCEL -> {
                val handled = pendingThumb != 0
                resetGesture()
                return handled
            }
        }
        return super.onTouchEvent(event)
    }

    private fun resetGesture() {
        parent?.requestDisallowInterceptTouchEvent(false)
        pendingThumb = 0
        dragging = 0
        rejectedVertical = false
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
