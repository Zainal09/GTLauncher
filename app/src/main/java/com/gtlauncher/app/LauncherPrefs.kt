package com.gtlauncher.app

import android.content.Context
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
    }

    private val p = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

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

    fun getList(key: String): List<String>? =
        p.getString(key, null)?.split(",")?.filter { it.isNotEmpty() }

    fun setList(key: String, list: List<String>) {
        p.edit().putString(key, list.joinToString(",")).apply()
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
