package com.gtlauncher.app

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

data class AppInfo(
    val label: String,
    val packageName: String,
    val component: ComponentName,
    val icon: Drawable
) {
    /** Kunci unik aplikasi: "paket/kelas". */
    val key: String get() = component.flattenToString()

    /** Bitmap ikon yang sudah dibulatkan (untuk digambar langsung, mis. di folder). */
    val bitmap: Bitmap? get() = (icon as? BitmapDrawable)?.bitmap
}
