package com.gtlauncher.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Semua pengaturan launcher disimpan di sini. */
class LauncherPrefs(context: Context) {

    companion object {
        val ICON_SIZES_DP = intArrayOf(52, 60, 68)
        val ICON_SIZE_NAMES = arrayOf("Kecil", "Sedang", "Besar")

        val GLASS_ALPHAS = intArrayOf(0x1F, 0x38, 0x66, 0x99)
        val GLASS_NAMES = arrayOf("Sangat transparan", "Transparan", "Sedang", "Pekat")

        val CLOCK_COLORS = intArrayOf(
            0xFFFFFFFF.toInt(),
            0xFF111111.toInt(),
            0xFFFFD60A.toInt(),
            0xFF64D2FF.toInt(),
            0xFFFF9FCE.toInt()
        )
        val CLOCK_COLOR_NAMES = arrayOf("Putih", "Hitam", "Kuning", "Biru muda", "Merah muda")

        val LABEL_COLORS = intArrayOf(0xFFFFFFFF.toInt(), 0xFF111111.toInt())
        val LABEL_COLOR_NAMES = arrayOf("Putih", "Hitam")

        val GRID_NAMES = arrayOf("4 x 4", "4 x 5", "4 x 6", "5 x 5", "5 x 6", "6 x 6", "6 x 7")
        val GRID_COLS = intArrayOf(4, 4, 4, 5, 5, 6, 6)
        val GRID_ROWS = intArrayOf(4, 5, 6, 5, 6, 6, 7)

        val THEME_NAMES = arrayOf("Otomatis", "Terang", "Gelap")
        val SHAPE_NAMES = arrayOf("Squircle", "Lingkaran", "Kotak", "Tetesan")
        val FONT_NAMES = arrayOf("Default", "Sans", "Serif", "Monospace", "Condensed", "Cursive")
        val FONT_FAMILIES = arrayOf("", "sans-serif", "serif", "monospace", "sans-serif-condensed", "cursive")
        val LABEL_SIZE_NAMES = arrayOf("Kecil", "Normal", "Besar", "Sangat besar")
        val LABEL_SIZE_SP = intArrayOf(9, 11, 13, 15)
        val ANIM_NAMES = arrayOf("Membesar (iOS)", "Pudar", "Geser", "Tanpa animasi")
        val ANIM_SPEEDS = arrayOf("Pelan", "Normal", "Cepat")
        val ANIM_SPEED_MULT = floatArrayOf(1.45f, 1f, 0.6f)
        val SORT_NAMES = arrayOf("A - Z", "Terbaru dipasang", "Paling sering dipakai")
        val SORT_KEYS = arrayOf("az", "newest", "frequent")
        val REFRESH_NAMES = arrayOf("Otomatis", "60 Hz", "90 Hz", "120 Hz")
        val REFRESH_HZ = intArrayOf(0, 60, 90, 120)
        val BADGE_NAMES = arrayOf("Titik", "Angka", "Mati")
        val LANGUAGE_NAMES = arrayOf("Bahasa Indonesia", "English")

        const val ACTION_NONE = "none"
        const val ACTION_DRAWER = "drawer"
        const val ACTION_NOTIFICATIONS = "notifications"
        const val ACTION_CONTROL_CENTER = "control_center"
        const val ACTION_FLASHLIGHT = "flashlight"
        const val ACTION_SEARCH = "search"
        const val ACTION_LOCK = "lock"
        const val ACTION_SCREENSHOT = "screenshot"
        const val ACTION_TODAY = "today"
        const val ACTION_SETTINGS = "settings"

        val GESTURE_ACTION_KEYS = arrayOf(
            ACTION_NONE, ACTION_DRAWER, ACTION_NOTIFICATIONS, ACTION_CONTROL_CENTER,
            ACTION_FLASHLIGHT, ACTION_SEARCH, ACTION_LOCK, ACTION_SCREENSHOT,
            ACTION_TODAY, ACTION_SETTINGS
        )
        val GESTURE_ACTION_NAMES = arrayOf(
            "Tidak ada", "Buka Pustaka Aplikasi", "Panel notifikasi", "Pusat kontrol",
            "Senter", "Cari (Spotlight)", "Kunci layar", "Tangkapan layar",
            "Halaman Hari Ini", "Pengaturan launcher"
        )

        val CATEGORY_NAMES = arrayOf("Semua", "Sosial", "Game", "Produktivitas", "Hiburan", "Lainnya")
    }

    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- tampilan

    var iconSizeIndex: Int
        get() = p.getInt("iconSize", 1).coerceIn(0, ICON_SIZES_DP.size - 1)
        set(v) { p.edit().putInt("iconSize", v).apply() }

    val iconSizeDp: Int get() = ICON_SIZES_DP[iconSizeIndex]

    var columns: Int
        get() = p.getInt("columns", 4).coerceIn(4, 6)
        set(v) { p.edit().putInt("columns", v).apply() }

    var rows: Int
        get() = p.getInt("rows", 5).coerceIn(4, 7)
        set(v) { p.edit().putInt("rows", v).apply() }

    var gridIndex: Int
        get() {
            val c = columns
            val r = rows
            for (i in GRID_COLS.indices) {
                if (GRID_COLS[i] == c && GRID_ROWS[i] == r) return i
            }
            return 1 // default: 4 x 5
        }
        set(v) {
            val idx = v.coerceIn(0, GRID_NAMES.size - 1)
            columns = GRID_COLS[idx]
            rows = GRID_ROWS[idx]
        }

    var autoGrid: Boolean
        get() = p.getBoolean("autoGrid", true)
        set(v) { p.edit().putBoolean("autoGrid", v).apply() }

    var lockLayout: Boolean
        get() = p.getBoolean("lockLayout", false)
        set(v) { p.edit().putBoolean("lockLayout", v).apply() }

    var showLabels: Boolean
        get() = p.getBoolean("showLabels", true)
        set(v) { p.edit().putBoolean("showLabels", v).apply() }

    var labelColorIndex: Int
        get() = p.getInt("labelColor", 0).coerceIn(0, LABEL_COLORS.size - 1)
        set(v) { p.edit().putInt("labelColor", v).apply() }

    var clockVisible: Boolean
        get() = p.getBoolean("clockVisible", true)
        set(v) { p.edit().putBoolean("clockVisible", v).apply() }

    var clock24: Boolean
        get() = p.getBoolean("clock24", true)
        set(v) { p.edit().putBoolean("clock24", v).apply() }

    var showDate: Boolean
        get() = p.getBoolean("showDate", true)
        set(v) { p.edit().putBoolean("showDate", v).apply() }

    var clockColorIndex: Int
        get() = p.getInt("clockColor", 0).coerceIn(0, CLOCK_COLORS.size - 1)
        set(v) { p.edit().putInt("clockColor", v).apply() }

    var glassIndex: Int
        get() = p.getInt("glass", 1).coerceIn(0, GLASS_ALPHAS.size - 1)
        set(v) { p.edit().putInt("glass", v).apply() }

    var highRefresh: Boolean
        get() = p.getBoolean("highRefresh", true)
        set(v) { p.edit().putBoolean("highRefresh", v).apply() }

    var hidden: Set<String>
        get() = (p.getString("hidden", "") ?: "").split(",").filter { it.isNotEmpty() }.toSet()
        set(v) { p.edit().putString("hidden", v.joinToString(",")).apply() }

    var widgetIds: List<Int>
        get() = (p.getString("widgets", "") ?: "").split(",").mapNotNull { it.toIntOrNull() }
        set(v) { p.edit().putString("widgets", v.joinToString(",")).apply() }

    var showDrawerButton: Boolean
        get() = p.getBoolean("showDrawerButton", false)
        set(v) { p.edit().putBoolean("showDrawerButton", v).apply() }

    var pageCount: Int
        get() = p.getInt("pageCount", 1).coerceAtLeast(1)
        set(v) { p.edit().putInt("pageCount", v.coerceAtLeast(1)).apply() }

    // ---------------------------------------------------------------- tema

    var themeMode: Int
        get() = p.getInt("themeMode", 0).coerceIn(0, 2)
        set(v) { p.edit().putInt("themeMode", v).apply() }

    var accentFromWallpaper: Boolean
        get() = p.getBoolean("accentFromWallpaper", true)
        set(v) { p.edit().putBoolean("accentFromWallpaper", v).apply() }

    var accentColor: Int
        get() = p.getInt("accentColor", 0xFF0A84FF.toInt())
        set(v) { p.edit().putInt("accentColor", v).apply() }

    var iconShape: Int
        get() = p.getInt("iconShape", 0).coerceIn(0, SHAPE_NAMES.size - 1)
        set(v) { p.edit().putInt("iconShape", v).apply() }

    /** 0 = ikuti bentuk, 100 = paling membulat. */
    var iconCorner: Int
        get() = p.getInt("iconCorner", 0).coerceIn(0, 100)
        set(v) { p.edit().putInt("iconCorner", v).apply() }

    var iconPack: String
        get() = p.getString("iconPack", "") ?: ""
        set(v) { p.edit().putString("iconPack", v).apply() }

    var fontIndex: Int
        get() = p.getInt("fontIndex", 0).coerceIn(0, FONT_NAMES.size - 1)
        set(v) { p.edit().putInt("fontIndex", v).apply() }

    var labelSizeIndex: Int
        get() = p.getInt("labelSize", 1).coerceIn(0, LABEL_SIZE_SP.size - 1)
        set(v) { p.edit().putInt("labelSize", v).apply() }

    var blurEnabled: Boolean
        get() = p.getBoolean("blur", true)
        set(v) { p.edit().putBoolean("blur", v).apply() }

    var parallax: Boolean
        get() = p.getBoolean("parallax", true)
        set(v) { p.edit().putBoolean("parallax", v).apply() }

    var animStyle: Int
        get() = p.getInt("animStyle", 0).coerceIn(0, ANIM_NAMES.size - 1)
        set(v) { p.edit().putInt("animStyle", v).apply() }

    var animSpeed: Int
        get() = p.getInt("animSpeed", 1).coerceIn(0, ANIM_SPEEDS.size - 1)
        set(v) { p.edit().putInt("animSpeed", v).apply() }

    val animMult: Float get() = ANIM_SPEED_MULT[animSpeed]

    var perPageWallpaper: Boolean
        get() = p.getBoolean("perPageWallpaper", false)
        set(v) { p.edit().putBoolean("perPageWallpaper", v).apply() }

    var dailyWallpaper: Boolean
        get() = p.getBoolean("dailyWallpaper", false)
        set(v) { p.edit().putBoolean("dailyWallpaper", v).apply() }

    var wallpaperUris: List<String>
        get() = getList("wallpaperUris") ?: emptyList()
        set(v) { setList("wallpaperUris", v) }

    var statusBarAuto: Boolean
        get() = p.getBoolean("statusBarAuto", true)
        set(v) { p.edit().putBoolean("statusBarAuto", v).apply() }

    // ---------------------------------------------------------------- pustaka

    var librarySort: Int
        get() = p.getInt("librarySort", 0).coerceIn(0, SORT_NAMES.size - 1)
        set(v) { p.edit().putInt("librarySort", v).apply() }

    var showRecentRow: Boolean
        get() = p.getBoolean("showRecentRow", true)
        set(v) { p.edit().putBoolean("showRecentRow", v).apply() }

    var showFrequentRow: Boolean
        get() = p.getBoolean("showFrequentRow", true)
        set(v) { p.edit().putBoolean("showFrequentRow", v).apply() }

    var showCategories: Boolean
        get() = p.getBoolean("showCategories", true)
        set(v) { p.edit().putBoolean("showCategories", v).apply() }

    var showLetterIndex: Boolean
        get() = p.getBoolean("showLetterIndex", true)
        set(v) { p.edit().putBoolean("showLetterIndex", v).apply() }

    // ---------------------------------------------------------------- pencarian

    var searchAutoOpen: Boolean
        get() = p.getBoolean("searchAutoOpen", true)
        set(v) { p.edit().putBoolean("searchAutoOpen", v).apply() }

    var searchContacts: Boolean
        get() = p.getBoolean("searchContacts", true)
        set(v) { p.edit().putBoolean("searchContacts", v).apply() }

    var searchSettings: Boolean
        get() = p.getBoolean("searchSettings", true)
        set(v) { p.edit().putBoolean("searchSettings", v).apply() }

    var searchWeb: Boolean
        get() = p.getBoolean("searchWeb", true)
        set(v) { p.edit().putBoolean("searchWeb", v).apply() }

    var searchHistory: List<String>
        get() = getList("searchHistory") ?: emptyList()
        set(v) { setList("searchHistory", v.take(20)) }

    var spotlightEnabled: Boolean
        get() = p.getBoolean("spotlight", true)
        set(v) { p.edit().putBoolean("spotlight", v).apply() }

    // ---------------------------------------------------------------- gesture

    var gestureUp: String
        get() = p.getString("gestureUp", ACTION_DRAWER) ?: ACTION_DRAWER
        set(v) { p.edit().putString("gestureUp", v).apply() }

    var gestureDown: String
        get() = p.getString("gestureDown", ACTION_NOTIFICATIONS) ?: ACTION_NOTIFICATIONS
        set(v) { p.edit().putString("gestureDown", v).apply() }

    var gestureDoubleTap: String
        get() = p.getString("gestureDoubleTap", ACTION_LOCK) ?: ACTION_LOCK
        set(v) { p.edit().putString("gestureDoubleTap", v).apply() }

    var gestureTwoFinger: String
        get() = p.getString("gestureTwoFinger", ACTION_CONTROL_CENTER) ?: ACTION_CONTROL_CENTER
        set(v) { p.edit().putString("gestureTwoFinger", v).apply() }

    var gesturePinch: String
        get() = p.getString("gesturePinch", ACTION_NONE) ?: ACTION_NONE
        set(v) { p.edit().putString("gesturePinch", v).apply() }

    var haptics: Boolean
        get() = p.getBoolean("haptics", true)
        set(v) { p.edit().putBoolean("haptics", v).apply() }

    // ---------------------------------------------------------------- notifikasi

    var badgesEnabled: Boolean
        get() = p.getBoolean("badges", true)
        set(v) { p.edit().putBoolean("badges", v).apply() }

    var badgeStyle: Int
        get() = p.getInt("badgeStyle", 1).coerceIn(0, 2)
        set(v) { p.edit().putInt("badgeStyle", v).apply() }

    var notificationPreview: Boolean
        get() = p.getBoolean("notifPreview", true)
        set(v) { p.edit().putBoolean("notifPreview", v).apply() }

    var controlCenterTiles: String
        get() = p.getString(
            "ccTiles",
            "flashlight,wifi,bluetooth,rotation,dnd,game,lock,settings"
        ) ?: ""
        set(v) { p.edit().putString("ccTiles", v).apply() }

    // ---------------------------------------------------------------- privasi

    var privateApps: Set<String>
        get() = getSet("privateApps")
        set(v) { setSet("privateApps", v) }

    var lockedApps: Set<String>
        get() = getSet("lockedApps")
        set(v) { setSet("lockedApps", v) }

    var appPinHash: String
        get() = p.getString("appPinHash", "") ?: ""
        set(v) { p.edit().putString("appPinHash", v).apply() }

    var appPinSalt: String
        get() = p.getString("appPinSalt", "") ?: ""
        set(v) { p.edit().putString("appPinSalt", v).apply() }

    var timeLimits: Map<String, Int>
        get() = getIntMap("timeLimits")
        set(v) { setIntMap("timeLimits", v) }

    // ---------------------------------------------------------------- gamer

    var gameModePackage: String
        get() = p.getString("gamePkg", "") ?: ""
        set(v) { p.edit().putString("gamePkg", v).apply() }

    var gameModeDnd: Boolean
        get() = p.getBoolean("gameDnd", true)
        set(v) { p.edit().putBoolean("gameDnd", v).apply() }

    var gameModeBrightness: Boolean
        get() = p.getBoolean("gameBrightness", true)
        set(v) { p.edit().putBoolean("gameBrightness", v).apply() }

    var gameFolder: Boolean
        get() = p.getBoolean("gameFolder", true)
        set(v) { p.edit().putBoolean("gameFolder", v).apply() }

    // ---------------------------------------------------------------- sistem

    var refreshMode: Int
        get() = p.getInt("refreshMode", 0).coerceIn(0, REFRESH_NAMES.size - 1)
        set(v) { p.edit().putInt("refreshMode", v).apply() }

    var batterySaver: Boolean
        get() = p.getBoolean("batterySaver", true)
        set(v) { p.edit().putBoolean("batterySaver", v).apply() }

    var language: String
        get() = p.getString("language", "in") ?: "in"
        set(v) { p.edit().putString("language", v).apply() }

    var newAppHighlight: Boolean
        get() = p.getBoolean("newAppHighlight", true)
        set(v) { p.edit().putBoolean("newAppHighlight", v).apply() }

    var lastSeenVersion: String
        get() = p.getString("lastSeenVersion", "") ?: ""
        set(v) { p.edit().putString("lastSeenVersion", v).apply() }

    // ---------------------------------------------------------------- widget & hari ini

    var weatherCache: String
        get() = p.getString("weatherCache", "") ?: ""
        set(v) { p.edit().putString("weatherCache", v).apply() }

    var weatherUpdated: Long
        get() = p.getLong("weatherUpdated", 0L)
        set(v) { p.edit().putLong("weatherUpdated", v).apply() }

    var weatherCity: String
        get() = p.getString("weatherCity", "Jakarta") ?: "Jakarta"
        set(v) { p.edit().putString("weatherCity", v).apply() }

    var weatherLat: Float
        get() = p.getFloat("weatherLat", -6.2088f)
        set(v) { p.edit().putFloat("weatherLat", v).apply() }

    var weatherLon: Float
        get() = p.getFloat("weatherLon", 106.8456f)
        set(v) { p.edit().putFloat("weatherLon", v).apply() }

    var smartStackOrder: List<String>
        get() = getList("stackOrder") ?: listOf("system", "weather", "calendar", "notes")
        set(v) { setList("stackOrder", v) }

    var todayNotes: String
        get() = p.getString("todayNotes", "") ?: ""
        set(v) { p.edit().putString("todayNotes", v).apply() }

    var tasksJson: String
        get() = p.getString("tasks", "[]") ?: "[]"
        set(v) { p.edit().putString("tasks", v).apply() }

    var favoriteContacts: List<String>
        get() = getList("favContacts") ?: emptyList()
        set(v) { setList("favContacts", v) }

    // ---------------------------------------------------------------- peta aplikasi

    var customLabels: MutableMap<String, String>
        get() = getStringMap("customLabels")
        set(v) { setStringMap("customLabels", v) }

    var customIcons: MutableMap<String, String>
        get() = getStringMap("customIcons")
        set(v) { setStringMap("customIcons", v) }

    // ---------------------------------------------------------------- util

    fun getList(key: String): List<String>? =
        p.getString(key, null)?.split(",")?.filter { it.isNotEmpty() }

    fun setList(key: String, list: List<String>) {
        p.edit().putString(key, list.joinToString(",")).apply()
    }

    private fun getSet(key: String): Set<String> {
        val raw = p.getStringSet(key, null)
        if (raw != null) return raw.toSet()
        return (p.getString(key, "") ?: "").split(",").filter { it.isNotEmpty() }.toSet()
    }

    private fun setSet(key: String, value: Set<String>) {
        p.edit().putStringSet(key, value).apply()
    }

    private fun getStringMap(key: String): MutableMap<String, String> {
        val out = mutableMapOf<String, String>()
        val raw = p.getString(key, "") ?: ""
        if (raw.isEmpty()) return out
        try {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.optString(k, "")
            }
        } catch (e: Exception) {
            // abaikan
        }
        return out
    }

    private fun setStringMap(key: String, map: Map<String, String>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        p.edit().putString(key, obj.toString()).apply()
    }

    private fun getIntMap(key: String): MutableMap<String, Int> {
        val out = mutableMapOf<String, Int>()
        val raw = p.getString(key, "") ?: ""
        if (raw.isEmpty()) return out
        try {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.optInt(k, 0)
            }
        } catch (e: Exception) {
            // abaikan
        }
        return out
    }

    private fun setIntMap(key: String, map: Map<String, Int>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        p.edit().putString(key, obj.toString()).apply()
    }

    fun snapshot(): Map<String, *> = p.all

    fun restore(values: Map<String, *>) {
        val editor = p.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> editor.putStringSet(key, value.mapNotNull { it as? String }.toSet())
            }
        }
        editor.apply()
    }

    /**
     * Membaca daftar item layout layar utama dalam format JSON.
     */
    fun getHomeItems(): List<LauncherItem> {
        val raw = p.getString("home_items_v2", null) ?: return emptyList()
        val items = mutableListOf<LauncherItem>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i)
                if (obj != null) {
                    items.add(LauncherItem.fromJson(obj))
                }
            }
        } catch (e: Exception) {
            // abaikan format rusak
        }
        return items
    }

    /**
     * Menyimpan daftar item layout layar utama ke SharedPreferences.
     */
    fun setHomeItems(items: List<LauncherItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        p.edit().putString("home_items_v2", arr.toString()).apply()

        // Perbarui pageCount otomatis berdasarkan halaman terjauh
        val maxPage = items.maxOfOrNull { it.page } ?: 0
        if (maxPage + 1 > pageCount) {
            pageCount = maxPage + 1
        }
    }

    /**
     * Membaca daftar item di dock.
     */
    fun getDockItems(): List<LauncherItem> {
        val raw = p.getString("dock_items_v2", null) ?: return emptyList()
        val items = mutableListOf<LauncherItem>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i)
                if (obj != null) {
                    items.add(LauncherItem.fromJson(obj))
                }
            }
        } catch (e: Exception) {
            // abaikan format rusak
        }
        return items
    }

    /**
     * Menyimpan daftar item di dock.
     */
    fun setDockItems(items: List<LauncherItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        p.edit().putString("dock_items_v2", arr.toString()).apply()
    }

    fun migrateOldDockIfNeeded(defaultPkgsSupplier: () -> List<String>): List<LauncherItem> {
        val existing = getDockItems()
        if (existing.isNotEmpty()) return existing

        val oldPkgs = getList("dock") ?: defaultPkgsSupplier()
        val migrated = oldPkgs.mapIndexed { idx, pkg ->
            LauncherItem(
                type = ItemType.APP,
                page = 0,
                cellX = idx,
                cellY = 0,
                packageName = pkg
            )
        }
        setDockItems(migrated)
        return migrated
    }

    /**
     * Migrasi dari data versi lama ("home") jika belum pernah disimpan di "home_items_v2".
     */
    fun migrateOldHomeIfNeeded(defaultPkgsSupplier: () -> List<String>): List<LauncherItem> {
        val existing = getHomeItems()
        if (existing.isNotEmpty()) return existing

        val oldPkgs = getList("home") ?: defaultPkgsSupplier()
        val migrated = mutableListOf<LauncherItem>()
        val cols = columns
        val maxRows = rows

        var curPage = 0
        var curX = 0
        var curY = 0

        for (pkg in oldPkgs) {
            migrated.add(
                LauncherItem(
                    type = ItemType.APP,
                    page = curPage,
                    cellX = curX,
                    cellY = curY,
                    spanX = 1,
                    spanY = 1,
                    packageName = pkg
                )
            )
            curX++
            if (curX >= cols) {
                curX = 0
                curY++
                if (curY >= maxRows) {
                    curY = 0
                    curPage++
                }
            }
        }

        setHomeItems(migrated)
        pageCount = (curPage + 1).coerceAtLeast(1)
        return migrated
    }

    fun resetLayout() {
        p.edit()
            .remove("home")
            .remove("home_items_v2")
            .remove("dock")
            .remove("dock_items_v2")
            .remove("pageCount")
            .apply()
    }
}
