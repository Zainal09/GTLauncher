package com.gtlauncher.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider

/** Rasio sudut ikon iOS (sekitar 22,37% dari lebar). */
const val ICON_RADIUS_RATIO = 0.2237f

/** Potong view jadi kotak bersudut membulat ala ikon iOS. */
fun View.applyIosCorners() {
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, view.width * ICON_RADIUS_RATIO)
        }
    }
    clipToOutline = true
}

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
 * Ubah ikon apa pun (bulat, kotak, adaptive) jadi bitmap persegi penuh,
 * supaya semua ikon seragam setelah dipotong bersudut iOS.
 */
fun Drawable.toIosBitmapDrawable(context: Context, sizePx: Int = 192): Drawable {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
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
    return BitmapDrawable(context.resources, bmp)
}
