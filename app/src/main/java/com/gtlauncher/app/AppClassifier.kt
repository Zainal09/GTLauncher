package com.gtlauncher.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** Kategori otomatis ala iOS App Library. */
object AppClassifier {

    private val SOCIAL = arrayOf(
        "whatsapp", "instagram", "facebook", "tiktok", "twitter", "telegram", "line",
        "wechat", "snapchat", "discord", "threads", "messenger", "signal", "viber",
        "pinterest", "reddit", "linkedin", "x.com"
    )
    private val PRODUCTIVITY = arrayOf(
        "office", "word", "excel", "powerpoint", "docs", "sheets", "slides", "drive",
        "notes", "keep", "notion", "gmail", "mail", "outlook", "calendar", "slack",
        "teams", "zoom", "meet", "files", "file manager", "scan", "translate"
    )
    private val ENTERTAINMENT = arrayOf(
        "youtube", "netflix", "spotify", "vlc", "mx player", "music", "video", "cinema",
        "disney", "prime video", "twitch", "podcast", "radio", "gallery", "photos",
        "camera", "capcut", "editor", "browser", "chrome", "firefox"
    )
    private val GAMES = arrayOf(
        "mobile legends", "mlbb", "pubg", "free fire", "cod mobile", "call of duty",
        "genshin", "honkai", "honor of kings", "minecraft", "roblox", "among us",
        "clash of clans", "clash royale", "efootball", "fifa", "asphalt", "game",
        "garena", "valorant", "league of legends", "wild rift", "arena of valor",
        "stumble guys", "subway surfers", "candy crush", "brawl stars", "fortnite"
    )
    private val GAME_PACKAGES = arrayOf(
        "com.mobile.legends", "com.garena.game", "com.tencent.ig", "com.dts.freefire",
        "com.mojang", "com.roblox", "com.miHoYo", "com.activision", "com.ea.",
        "com.riotgames", "com.supercell", "com.kiloo", "com.imangi", "com.innersloth"
    )

    fun categoryIndex(app: AppInfo): Int {
        if (app.isGame) return 2
        return when (app.category) {
            ApplicationInfo.CATEGORY_SOCIAL -> 1
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> 3
            ApplicationInfo.CATEGORY_AUDIO,
            ApplicationInfo.CATEGORY_VIDEO,
            ApplicationInfo.CATEGORY_IMAGE,
            ApplicationInfo.CATEGORY_NEWS,
            ApplicationInfo.CATEGORY_MAPS -> 4
            else -> byKeyword(app.packageName, app.label)
        }
    }

    fun byKeyword(packageName: String, label: String): Int {
        val haystack = "$packageName $label".lowercase()
        if (GAMES.any { haystack.contains(it) }) return 2
        if (SOCIAL.any { haystack.contains(it) }) return 1
        if (PRODUCTIVITY.any { haystack.contains(it) }) return 3
        if (ENTERTAINMENT.any { haystack.contains(it) }) return 4
        return 5
    }

    fun isGame(packageName: String, label: String, category: Int): Boolean {
        if (category == ApplicationInfo.CATEGORY_GAME) return true
        if (GAME_PACKAGES.any { packageName.startsWith(it) }) return true
        val haystack = "$packageName $label".lowercase()
        return GAMES.any { haystack.contains(it) }
    }

    fun loadCategory(context: Context, packageName: String): Int {
        return try {
            context.packageManager.getApplicationInfo(packageName, 0).category
        } catch (e: Exception) {
            ApplicationInfo.CATEGORY_UNDEFINED
        }
    }
}
