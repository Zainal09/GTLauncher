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
fun Drawable.toIosBitmapDrawable(context: Context, sizePx: Int = 192): Drawable {
    val content = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(content)
    val d = this
    if (d is AdaptiveIconDrawable) {
        // Layer adaptive berukuran 108dp, area terlihat 72dp -> perbesar 1,5x agar area terlihat memenuhi bitmap
        val pad = (sizePx * 0.25f).toInt()
        val bounds = Rect(-pad, -pad, sizePx + pad, sizePx + pad)
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
        val inset = (sizePx * 0.14f).toInt()
        d.setBounds(inset, inset, sizePx - inset, sizePx - inset)
        d.draw(canvas)
    }

    // Potong jadi kotak membulat yang halus (anti-alias)
    val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val oc = Canvas(out)
    val radius = sizePx * ICON_RADIUS_RATIO
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    oc.drawRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), radius, radius, paint)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    oc.drawBitmap(content, 0f, 0f, paint)
    content.recycle()
    return BitmapDrawable(context.resources, out)
}
