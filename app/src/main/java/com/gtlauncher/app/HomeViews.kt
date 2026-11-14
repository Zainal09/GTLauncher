package com.gtlauncher.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView

/**
 * Lapisan paling atas. Saat ikon sedang diseret, lapisan ini mengambil alih semua sentuhan
 * (menggantikan ViewPager, ikon, dan widget) dan meneruskannya ke [Host].
 */
class DragLayer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    interface Host {
        fun onDragTouch(ev: MotionEvent)
    }

    var host: Host? = null
    var dragActive = false
    var lastRawX = 0f
    var lastRawY = 0f

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastRawX = ev.rawX
        lastRawY = ev.rawY
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (dragActive) {
            host?.onDragTouch(ev)
            return true
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (dragActive) {
            host?.onDragTouch(ev)
            return true
        }
        return super.onTouchEvent(ev)
    }
}

/** Satu halaman layar utama: menaruh [CellView] sesuai kolom, baris, dan ukurannya. */
class HomePageView(context: Context) : ViewGroup(context) {

    var cols = 4
    var rows = 6
    var pageIndex = -1

    val padPx: Int = (16 * context.resources.displayMetrics.density).toInt()

    init {
        clipChildren = false
        clipToPadding = false
    }

    fun cellW(): Float = if (cols > 0) (width - 2f * padPx) / cols else 0f

    fun cellH(): Float = if (rows > 0) height.toFloat() / rows else 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = View.MeasureSpec.getSize(widthMeasureSpec)
        val h = View.MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val cw = (w - 2f * padPx) / cols
        val ch = h.toFloat() / rows
        for (i in 0 until childCount) {
            val cell = getChildAt(i) as? CellView ?: continue
            val wPx = Math.round(cw * cell.item.spanW).coerceAtLeast(0)
            val hPx = Math.round(ch * cell.item.spanH).coerceAtLeast(0)
            cell.measure(
                View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = (width - 2f * padPx) / cols
        val ch = height.toFloat() / rows
        for (i in 0 until childCount) {
            val cell = getChildAt(i) as? CellView ?: continue
            val left = padPx + Math.round(cw * cell.item.col)
            val top = Math.round(ch * cell.item.row)
            cell.layout(left, top, left + cell.measuredWidth, top + cell.measuredHeight)
        }
    }
}

/**
 * Satu sel di layar utama atau dock: ikon, folder, widget, atau jam.
 * Mengurus deteksi tahan-lama sendiri, lencana hapus, titik "baru", dan animasi bergoyang.
 */
@SuppressLint("ViewConstructor")
class CellView(context: Context, val item: HomeItem) : FrameLayout(context) {

    var onLongPress: (() -> Unit)? = null
    var onBadge: (() -> Unit)? = null
    var sizeListener: ((Int, Int) -> Unit)? = null
    var editing = false
        private set

    private val d = context.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val badge = TextView(context)
    private val dot = View(context)
    private var content: View? = null
    private var wiggle: ObjectAnimator? = null
    private var downX = 0f
    private var downY = 0f

    private val longPress = Runnable { onLongPress?.invoke() }

    init {
        clipChildren = false
        clipToPadding = false
        isClickable = true

        badge.text = if (item.isIcon) "−" else "×"
        badge.setTextColor(0xFFFFFFFF.toInt())
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        badge.typeface = Typeface.DEFAULT_BOLD
        badge.gravity = Gravity.CENTER
        badge.includeFontPadding = false
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(0xE63A3A3C.toInt())
        bg.setStroke((1.5f * d).toInt(), 0xFFFFFFFF.toInt())
        badge.background = bg
        badge.visibility = View.GONE
        addView(badge, LayoutParams((22 * d).toInt(), (22 * d).toInt(), Gravity.TOP or Gravity.START))

        val dg = GradientDrawable()
        dg.shape = GradientDrawable.OVAL
        dg.setColor(0xFF0A84FF.toInt())
        dg.setStroke((1.5f * d).toInt(), 0xFFFFFFFF.toInt())
        dot.background = dg
        dot.visibility = View.GONE
        addView(dot, LayoutParams((12 * d).toInt(), (12 * d).toInt(), Gravity.TOP or Gravity.START))
    }

    fun setContent(v: View, lp: LayoutParams) {
        content?.let { removeView(it) }
        content = v
        addView(v, 0, lp)
    }

    fun setFresh(b: Boolean) {
        dot.visibility = if (b) View.VISIBLE else View.GONE
    }

    fun setEditing(b: Boolean) {
        editing = b
        badge.visibility = if (b) View.VISIBLE else View.GONE
        if (b && !item.isIcon) {
            val frame = GradientDrawable()
            frame.shape = GradientDrawable.RECTANGLE
            frame.cornerRadius = 22 * d
            frame.setColor(0x00000000)
            frame.setStroke((2 * d).toInt(), 0xCCFFFFFF.toInt())
            foreground = frame
        } else {
            foreground = null
        }
        if (b && item.isIcon) startWiggle() else stopWiggle()
    }

    private fun startWiggle() {
        if (wiggle != null) return
        val a = ObjectAnimator.ofFloat(this, View.ROTATION, -1.6f, 1.6f)
        a.duration = 110L + (Math.random() * 40).toLong()
        a.repeatCount = ValueAnimator.INFINITE
        a.repeatMode = ValueAnimator.REVERSE
        a.startDelay = (Math.random() * 80).toLong()
        a.start()
        wiggle = a
    }

    private fun stopWiggle() {
        wiggle?.cancel()
        wiggle = null
        rotation = 0f
    }

    /** Gambar tanpa lencana dan titik, untuk bayangan saat diseret. */
    fun snapshotClean(): Bitmap? {
        val bv = badge.visibility
        val dv = dot.visibility
        badge.visibility = View.GONE
        dot.visibility = View.GONE
        val bmp = snapshot()
        badge.visibility = bv
        dot.visibility = dv
        return bmp
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (editing && item.isIcon) startWiggle()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        wiggle?.cancel()
        wiggle = null
        rotation = 0f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sizeListener?.invoke(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        positionDecor()
    }

    private fun positionDecor() {
        val c = content ?: return
        var x = c.left
        var y = c.top
        var iw = c.width
        if (item.isIcon && c is ViewGroup && c.childCount > 0) {
            val ic = c.getChildAt(0)
            x = c.left + ic.left
            y = c.top + ic.top
            iw = ic.width
        }
        badge.translationX = x - 8 * d
        badge.translationY = y - 8 * d
        dot.translationX = x + iw - 7 * d
        dot.translationY = y - 3 * d
    }

    private fun hitBadge(): Boolean {
        val cx = badge.left + badge.translationX + badge.width / 2f
        val cy = badge.top + badge.translationY + badge.height / 2f
        val r = 24 * d
        return Math.abs(downX - cx) <= r && Math.abs(downY - cy) <= r
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Saat mode edit, semua sentuhan milik sel (widget tidak boleh menangkapnya)
        return editing
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                removeCallbacks(longPress)
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                if (!editing && item.isIcon) {
                    animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (Math.abs(ev.x - downX) > slop || Math.abs(ev.y - downY) > slop) {
                    removeCallbacks(longPress)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (scaleX != 1f) animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun performClick(): Boolean {
        if (editing && badge.visibility == View.VISIBLE && hitBadge()) {
            onBadge?.invoke()
            return true
        }
        return super.performClick()
    }
}

/** Ikon folder: latar kaca dengan sampai 9 ikon kecil di dalamnya. */
class FolderDrawable(private val bitmaps: List<Bitmap>) : Drawable() {

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init {
        bg.color = 0x66FFFFFF
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val r = w * ICON_RADIUS_RATIO
        canvas.drawRoundRect(RectF(b), r, r, bg)
        val pad = w * 0.11f
        val gap = w * 0.04f
        val cell = (w - 2f * pad - 2f * gap) / 3f
        val n = Math.min(9, bitmaps.size)
        for (i in 0 until n) {
            val row = i / 3
            val col = i % 3
            val l = b.left + pad + col * (cell + gap)
            val t = b.top + pad + row * (cell + gap)
            canvas.drawBitmap(bitmaps[i], null, RectF(l, t, l + cell, t + cell), p)
        }
    }

    override fun setAlpha(alpha: Int) {
        p.alpha = alpha
        bg.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        p.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Widget jam dan tanggal bawaan. Ukuran teks menyesuaikan ukuran widget. */
class ClockView(context: Context) : LinearLayout(context) {

    private val sd = context.resources.displayMetrics.scaledDensity
    private val d = context.resources.displayMetrics.density
    private val date = TextClock(context)
    private val time = TextClock(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setBackgroundResource(R.drawable.bg_widget)
        val pad = (20 * d).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)

        date.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        date.setAllCaps(true)
        date.format12Hour = "EEEE, d MMMM"
        date.format24Hour = "EEEE, d MMMM"
        date.includeFontPadding = false
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)

        time.typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        time.includeFontPadding = false
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 60f)

        addView(date, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(time, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun applyPrefs(cfg: LauncherPrefs) {
        val fmt = if (cfg.clock24) "HH:mm" else "h:mm"
        time.format24Hour = fmt
        time.format12Hour = fmt
        val color = LauncherPrefs.CLOCK_COLORS[cfg.clockColorIndex]
        time.setTextColor(color)
        date.setTextColor((color and 0x00FFFFFF) or 0xCC000000.toInt())
        date.visibility = if (cfg.showDate) View.VISIBLE else View.GONE
        val fill = android.graphics.Color.argb(LauncherPrefs.GLASS_ALPHAS[cfg.glassIndex], 255, 255, 255)
        (background?.mutate() as? GradientDrawable)?.setColor(fill)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val t = Math.max(Math.min(h * 0.5f, w * 0.22f), 24 * d)
        time.setTextSize(TypedValue.COMPLEX_UNIT_PX, t)
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(t * 0.2f, 11 * sd))
    }
}
