package com.gtlauncher.app

import android.content.Context

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
    }

    private val p = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    var iconSizeIndex: Int
        get() = p.getInt("iconSize", 1).coerceIn(0, ICON_SIZES_DP.size - 1)
        set(v) { p.edit().putInt("iconSize", v).apply() }

    val iconSizeDp: Int get() = ICON_SIZES_DP[iconSizeIndex]

    /** Jumlah kolom Pustaka Aplikasi (daftar semua aplikasi). */
    var columns: Int
        get() = p.getInt("columns", 4).coerceIn(4, 5)
        set(v) { p.edit().putInt("columns", v).apply() }

    /** Ukuran grid layar utama: kolom 4-6, baris 5-7. */
    var gridCols: Int
        get() = p.getInt("gridCols", 4).coerceIn(4, 6)
        set(v) { p.edit().putInt("gridCols", v).apply() }

    var gridRows: Int
        get() = p.getInt("gridRows", 6).coerceIn(5, 7)
        set(v) { p.edit().putInt("gridRows", v).apply() }

    /** Kunci tata letak: ikon tidak bisa digeser atau dihapus tanpa sengaja. */
    var layoutLocked: Boolean
        get() = p.getBoolean("layoutLocked", false)
        set(v) { p.edit().putBoolean("layoutLocked", v).apply() }

    /** Aplikasi yang baru dipasang otomatis masuk ke layar utama. */
    var autoAddNew: Boolean
        get() = p.getBoolean("autoAddNew", true)
        set(v) { p.edit().putBoolean("autoAddNew", v).apply() }

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

    /** ID widget dari versi lama (sebelum tata letak baru). Hanya dipakai saat migrasi. */
    var widgetIds: List<Int>
        get() = (p.getString("widgets", "") ?: "").split(",").mapNotNull { it.toIntOrNull() }
        set(v) { p.edit().putString("widgets", v.joinToString(",")).apply() }

    /** Tata letak layar utama dan dock (JSON). Null berarti belum pernah disimpan. */
    var layoutJson: String?
        get() = p.getString("layout", null)
        set(v) {
            if (v == null) p.edit().remove("layout").apply()
            else p.edit().putString("layout", v).apply()
        }

    /** Aplikasi yang sudah pernah dilihat launcher; yang belum ada di sini dianggap baru dipasang. */
    var known: Set<String>
        get() = (p.getString("known", "") ?: "").split(",").filter { it.isNotEmpty() }.toSet()
        set(v) { p.edit().putString("known", v.joinToString(",")).apply() }

    /** Aplikasi baru yang belum pernah dibuka (diberi titik sorotan). */
    var fresh: Set<String>
        get() = (p.getString("fresh", "") ?: "").split(",").filter { it.isNotEmpty() }.toSet()
        set(v) { p.edit().putString("fresh", v.joinToString(",")).apply() }

    fun getList(key: String): List<String>? =
        p.getString(key, null)?.split(",")?.filter { it.isNotEmpty() }

    fun setList(key: String, list: List<String>) {
        p.edit().putString(key, list.joinToString(",")).apply()
    }

    /** Hapus data tata letak lama dan baru; tata letak bawaan dibuat ulang oleh launcher. */
    fun resetLayout() {
        p.edit().remove("home").remove("dock").remove("layout").apply()
    }
}
