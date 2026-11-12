package com.gtlauncher.app

import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.util.Calendar

/** Membaca Usage Access untuk baris terakhir dipakai, paling sering, dan batas waktu. */
object UsageStatsHelper {

    fun hasAccess(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            if (mode == AppOpsManager.MODE_ALLOWED) return true
            // Beberapa perangkat mengembalikan MODE_DEFAULT walau akses sudah diberikan.
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm?.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 86_400_000L, now)
            return stats?.any { it.totalTimeInForeground > 0 } == true
        } catch (e: Exception) {
            false
        }
    }

    fun openAccessSettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (e: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                // abaikan
            }
        }
    }

    private fun manager(context: Context): UsageStatsManager? =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    private fun startOfToday(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun todayUsage(context: Context, packageName: String): Long {
        val usm = manager(context) ?: return 0
        return try {
            val now = System.currentTimeMillis()
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfToday(), now)
                ?.filter { it.packageName == packageName }
                ?.sumOf { it.totalTimeInForeground } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun weekUsage(context: Context, packageName: String): Long {
        val usm = manager(context) ?: return 0
        return try {
            val now = System.currentTimeMillis()
            val start = now - 7L * 24 * 60 * 60 * 1000
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)
                ?.filter { it.packageName == packageName }
                ?.sumOf { it.totalTimeInForeground } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun lastUsed(context: Context, packageName: String): Long {
        val usm = manager(context) ?: return 0
        return try {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfToday() - 30L * 24 * 60 * 60 * 1000, System.currentTimeMillis())
                ?.filter { it.packageName == packageName }
                ?.maxOfOrNull { it.lastTimeUsed } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /** Tambahkan statistik pemakaian ke daftar aplikasi (dipanggil di background). */
    fun enrich(context: Context, apps: List<AppInfo>) {
        if (!hasAccess(context)) return
        val usm = manager(context) ?: return
        try {
            val now = System.currentTimeMillis()
            val weekStats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 7L * 24 * 60 * 60 * 1000, now)
                ?: return
            val byPackage = weekStats.groupBy { it.packageName }
            val todayStart = startOfToday()
            for (app in apps) {
                val list = byPackage[app.packageName]
                if (list != null) {
                    app.usageWeekMs = list.sumOf { it.totalTimeInForeground }
                    app.lastUsed = list.maxOfOrNull { it.lastTimeUsed } ?: 0L
                    app.usageTodayMs = list.filter { it.lastTimeUsed >= todayStart }
                        .sumOf { it.totalTimeInForeground }
                }
            }
        } catch (e: Exception) {
            // abaikan
        }
    }

    data class UsageEntry(val packageName: String, val totalMs: Long, val lastUsed: Long)

    fun recent(context: Context, limit: Int): List<UsageEntry> = aggregate(context, limit) { it.lastUsed }

    fun frequent(context: Context, limit: Int): List<UsageEntry> = aggregate(context, limit) { it.totalMs }

    private fun aggregate(
        context: Context,
        limit: Int,
        selector: (UsageEntry) -> Long
    ): List<UsageEntry> {
        val usm = manager(context) ?: return emptyList()
        return try {
            val now = System.currentTimeMillis()
            val stats: List<UsageStats> = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - 7L * 24 * 60 * 60 * 1000,
                now
            ) ?: return emptyList()
            stats.groupBy { it.packageName }
                .map { (pkg, list) ->
                    UsageEntry(
                        pkg,
                        list.sumOf { it.totalTimeInForeground },
                        list.maxOfOrNull { it.lastTimeUsed } ?: 0L
                    )
                }
                .filter { it.packageName != context.packageName && it.totalMs > 0 }
                .sortedByDescending(selector)
                .take(limit)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun gameTimeToday(context: Context, apps: List<AppInfo>): Long {
        if (!hasAccess(context)) return 0
        return apps.filter { it.isGame }.sumOf { todayUsage(context, it.packageName) }
    }

    fun formatDuration(millis: Long): String {
        if (millis <= 0) return "0 mnt"
        val totalMinutes = millis / 60000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "$hours j $minutes mnt" else "$minutes mnt"
    }
}
