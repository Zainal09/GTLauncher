package com.gtlauncher.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View

/** Rasio sudut ikon iOS (sekitar 22,37% dari lebar). */
const val ICON_RADIUS_RATIO = 0.2237f

/** Efek mengecil sedikit saat disentuh, seperti ikon iOS. */
@SuppressLint("ClickableViewAccessibility")
fun View.pressEffect() {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }
        false
    }
}

/**
 * Ubah ikon apa pun (bulat, kotak, adaptive) jadi bitmap kotak bersudut membulat ala iOS.
 * Pembulatan dilakukan SEKALI di sini, jadi saat scroll GPU cukup menggambar bitmap biasa
 * (tanpa clip per ikon) dan scroll jauh lebih mulus.
 */
fun Drawable.toIosBitmapDrawable(context: Context, sizePx: Int = 0): Drawable {
    val targetPx = if (sizePx > 0) sizePx else (72 * context.resources.displayMetrics.density).toInt().coerceAtLeast(192)
    val content = Bitmap.createBitmap(targetPx, targetPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(content)
    val d = this
    if (d is AdaptiveIconDrawable) {
        // Layer adaptive berukuran 108dp, area terlihat 72dp -> perbesar 1,5x agar area terlihat memenuhi bitmap
        val pad = (targetPx * 0.25f).toInt()
        val bounds = Rect(-pad, -pad, targetPx + pad, targetPx + pad)
        d.background?.let {
            it.bounds = bounds
            it.draw(canvas)
        }
        d.foreground?.let {
            it.bounds = bounds
            it.draw(canvas)
        }
    } else {
        // Ikon biasa: beri latar terang lembut lalu gambar di tengah
        canvas.drawColor(Color.parseColor("#F2F2F7"))
        val inset = (targetPx * 0.14f).toInt()
        d.setBounds(inset, inset, targetPx - inset, targetPx - inset)
        d.draw(canvas)
    }

    // Potong jadi kotak membulat yang halus (anti-alias)
    val out = Bitmap.createBitmap(targetPx, targetPx, Bitmap.Config.ARGB_8888)
    val oc = Canvas(out)
    val radius = targetPx * ICON_RADIUS_RATIO
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    oc.drawRoundRect(RectF(0f, 0f, targetPx.toFloat(), targetPx.toFloat()), radius, radius, paint)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    oc.drawBitmap(content, 0f, 0f, paint)
    content.recycle()
    return BitmapDrawable(context.resources, out)
}

/**
 * Animasi goyang ala iOS (Jiggle Mode).
 */
fun View.startJiggle(seed: Int = 0) {
    val delay = ((seed * 37) % 120).toLong()
    val rot = if (seed % 2 == 0) 1.6f else -1.6f
    val anim = android.view.animation.RotateAnimation(
        -rot, rot,
        android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
        android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
    ).apply {
        duration = 135
        repeatMode = android.view.animation.Animation.REVERSE
        repeatCount = android.view.animation.Animation.INFINITE
        startOffset = delay
        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
    }
    startAnimation(anim)
}

fun View.stopJiggle() {
    clearAnimation()
    rotation = 0f
}

/**
 * Membuat pratinjau ikon folder kaca dengan mini grid aplikasi di dalamnya ala iOS.
 */
fun createFolderIcon(context: Context, icons: List<Drawable>, sizePx: Int = 0): Drawable {
    val targetPx = if (sizePx > 0) sizePx else (72 * context.resources.displayMetrics.density).toInt().coerceAtLeast(192)
    val out = Bitmap.createBitmap(targetPx, targetPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)

    // Latar belakang kaca squircle
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4DFFFFFF")
        style = Paint.Style.FILL
    }
    val radius = targetPx * ICON_RADIUS_RATIO
    canvas.drawRoundRect(RectF(0f, 0f, targetPx.toFloat(), targetPx.toFloat()), radius, radius, bgPaint)

    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 1f * context.resources.displayMetrics.density
    }
    canvas.drawRoundRect(RectF(0f, 0f, targetPx.toFloat(), targetPx.toFloat()), radius, radius, strokePaint)

    if (icons.isEmpty()) return BitmapDrawable(context.resources, out)

    val pad = targetPx * 0.15f
    val gap = targetPx * 0.04f
    val avail = targetPx - 2 * pad
    val gridSize = if (icons.size <= 4) 2 else 3
    val itemSize = (avail - (gridSize - 1) * gap) / gridSize
    val miniRadius = itemSize * ICON_RADIUS_RATIO
    val count = minOf(icons.size, gridSize * gridSize)

    for (i in 0 until count) {
        val col = i % gridSize
        val row = i / gridSize
        val left = pad + col * (itemSize + gap)
        val top = pad + row * (itemSize + gap)

        val iSize = itemSize.toInt().coerceAtLeast(1)
        val miniBitmap = Bitmap.createBitmap(iSize, iSize, Bitmap.Config.ARGB_8888)
        val mc = Canvas(miniBitmap)
        val d = icons[i]
        d.setBounds(0, 0, iSize, iSize)
        d.draw(mc)

        val roundedMini = Bitmap.createBitmap(iSize, iSize, Bitmap.Config.ARGB_8888)
        val rc = Canvas(roundedMini)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        rc.drawRoundRect(RectF(0f, 0f, iSize.toFloat(), iSize.toFloat()), miniRadius, miniRadius, p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        rc.drawBitmap(miniBitmap, 0f, 0f, p)
        miniBitmap.recycle()

        canvas.drawBitmap(roundedMini, left, top, null)
        roundedMini.recycle()
    }

    return BitmapDrawable(context.resources, out)
}
