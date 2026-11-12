package com.gtlauncher.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.viewpager2.widget.ViewPager2

/**
 * Indikator titik halaman bergaya iOS.
 * Mendukung animasi pergeseran halus saat ViewPager2 di-scroll.
 */
class PageIndicatorDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var pageCount: Int = 1
        set(value) {
            field = value.coerceAtLeast(1)
            visibility = if (field > 1) VISIBLE else GONE
            requestLayout()
            invalidate()
        }

    private var currentPosition: Int = 0
    private var positionOffset: Float = 0f

    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55FFFFFF")
        style = Paint.Style.FILL
    }

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val dotRadius = dp(3.5f)
    private val dotSpacing = dp(14f)

    private var attachedPager: ViewPager2? = null

    fun attachToViewPager(pager: ViewPager2) {
        attachedPager = pager
        pageCount = pager.adapter?.itemCount ?: 1
        currentPosition = pager.currentItem
        positionOffset = 0f

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, offset: Float, offsetPixels: Int) {
                currentPosition = position
                positionOffset = offset
                invalidate()
            }

            override fun onPageSelected(position: Int) {
                currentPosition = position
                positionOffset = 0f
                invalidate()
            }
        })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = dp(24f).toInt()
        val totalWidth = if (pageCount > 0) (pageCount * dotSpacing) else 0f
        val desiredWidth = (totalWidth + paddingLeft + paddingRight).toInt()

        val w = resolveSize(desiredWidth, widthMeasureSpec)
        val h = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pageCount <= 1) return

        val totalDotsWidth = (pageCount - 1) * dotSpacing
        val startX = (width - totalDotsWidth) / 2f
        val centerY = height / 2f

        // Gambar semua titik tidak aktif
        for (i in 0 until pageCount) {
            val cx = startX + i * dotSpacing
            canvas.drawCircle(cx, centerY, dotRadius, inactivePaint)
        }

        // Gambar titik aktif yang bergeser halus
        val activeX = startX + (currentPosition + positionOffset) * dotSpacing
        canvas.drawCircle(activeX, centerY, dotRadius * 1.15f, activePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && pageCount > 1) {
            val pager = attachedPager ?: return super.onTouchEvent(event)
            if (event.x < width / 2f) {
                if (pager.currentItem > 0) {
                    pager.currentItem -= 1
                    return true
                }
            } else {
                if (pager.currentItem < pageCount - 1) {
                    pager.currentItem += 1
                    return true
                }
            }
        }
        return true
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
