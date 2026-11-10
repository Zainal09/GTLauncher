package com.gtlauncher.app

import android.content.ComponentName
import android.graphics.drawable.Drawable

data class AppInfo(
    val label: String,
    val packageName: String,
    val component: ComponentName,
    val icon: Drawable
)
