package com.gtlauncher.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** Indeks huruf A-Z di tepi kanan Pustaka Aplikasi (fast scroller). */
class AlphabetIndexView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onLetterSelected: ((Char) -> Unit)? = null

    private val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ#"
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        textSize = 9f * resources.displayMetrics.scaledDensity
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF0A84FF")
    }
    private var activeIndex = -1

    fun setTextColor(color: Int) {
        paint.color = color
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (24 * resources.displayMetrics.density).toInt()
        val w = resolveSize(desiredWidth, widthMeasureSpec)
        val h = resolveSize((letters.length * 15 * resources.displayMetrics.density).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (height <= 0) return
        val step = height.toFloat() / letters.length
        val cx = width / 2f
        val fm = paint.fontMetrics
        val baselineOffset = -(fm.ascent + fm.descent) / 2f
        for (i in letters.indices) {
            val cy = (i + 0.5f) * step
            if (i == activeIndex) {
                canvas.drawCircle(cx, cy, step * 0.52f, dotPaint)
            }
            canvas.drawText(letters[i].toString(), cx, cy + baselineOffset, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val idx = (event.y / height * letters.length).toInt()
                    .coerceIn(0, letters.length - 1)
                if (idx != activeIndex) {
                    activeIndex = idx
                    invalidate()
                    onLetterSelected?.invoke(letters[idx])
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
