package com.gtlauncher.app

import android.content.ComponentName
import android.graphics.drawable.Drawable

data class AppInfo(
    var label: String,
    val packageName: String,
    val component: ComponentName,
    var icon: Drawable,
    val category: Int = 0,
    val isGame: Boolean = false,
    val installTime: Long = 0L,
    var usageTodayMs: Long = 0L,
    var usageWeekMs: Long = 0L,
    var lastUsed: Long = 0L
)
