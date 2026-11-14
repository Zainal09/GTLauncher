package com.gtlauncher.app

/** Jenis isi layar utama. */
object ItemType {
    const val APP = 0
    const val FOLDER = 1
    const val WIDGET = 2
    const val CLOCK = 3
}

/**
 * Satu isi layar utama: ikon aplikasi, folder, widget Android, atau widget jam bawaan.
 * Posisi memakai halaman + baris + kolom, ukuran memakai jumlah sel (spanW x spanH).
 * Item di dock tidak memakai posisi; urutannya mengikuti urutan di daftar dock.
 */
class HomeItem(val id: String, val type: Int) {
    var page = 0
    var row = 0
    var col = 0
    var spanW = 1
    var spanH = 1

    /** Kunci komponen aplikasi ("paket/kelas"), untuk jenis APP. */
    var component = ""

    /** Nama folder, untuk jenis FOLDER. */
    var name = ""

    /** Isi folder (kunci komponen aplikasi), untuk jenis FOLDER. */
    val children = mutableListOf<String>()

    /** ID widget Android, untuk jenis WIDGET. */
    var widgetId = -1

    val isIcon: Boolean get() = type == ItemType.APP || type == ItemType.FOLDER

    fun covers(p: Int, r: Int, c: Int): Boolean =
        page == p && r >= row && r < row + spanH && c >= col && c < col + spanW
}

/**
 * Seluruh tata letak: item di halaman layar utama + item di dock.
 * Kelas ini murni logika (tanpa Android) supaya mudah diuji.
 */
class HomeLayout {

    companion object {
        const val MAX_PAGES = 8
    }

    val items = mutableListOf<HomeItem>()
    val dock = mutableListOf<HomeItem>()
    var pageCount = 1
    var cols = 4
    var rows = 6

    private var counter = 0L

    fun newId(): String {
        counter += 1
        return "i" + System.currentTimeMillis().toString(36) + counter.toString(36)
    }

    fun itemsOnPage(p: Int): List<HomeItem> = items.filter { it.page == p }

    /** Item yang menutupi sel (p, r, c), selain [ignore]. */
    fun itemAt(p: Int, r: Int, c: Int, ignore: HomeItem? = null): HomeItem? {
        for (o in items) {
            if (o !== ignore && o.covers(p, r, c)) return o
        }
        return null
    }

    /** Apakah kotak w x h di (p, r, c) muat di dalam grid dan tidak menimpa item lain (selain [ignore]). */
    fun fits(p: Int, r: Int, c: Int, w: Int, h: Int, ignore: HomeItem?): Boolean {
        if (p < 0 || p >= pageCount) return false
        if (r < 0 || c < 0 || w < 1 || h < 1) return false
        if (r + h > rows || c + w > cols) return false
        for (o in items) {
            if (o === ignore || o.page != p) continue
            val overlap = c < o.col + o.spanW && c + w > o.col && r < o.row + o.spanH && r + h > o.row
            if (overlap) return false
        }
        return true
    }

    /** Cari slot kosong pertama untuk kotak w x h, mulai dari halaman [startPage] lalu halaman sebelumnya. */
    fun findFreeSlot(w: Int, h: Int, startPage: Int, ignore: HomeItem? = null): IntArray? {
        val first = startPage.coerceIn(0, maxOf(0, pageCount - 1))
        val order = mutableListOf<Int>()
        for (p in first until pageCount) order.add(p)
        for (p in 0 until first) order.add(p)
        for (p in order) {
            for (r in 0..(rows - h)) {
                for (c in 0..(cols - w)) {
                    if (fits(p, r, c, w, h, ignore)) return intArrayOf(p, r, c)
                }
            }
        }
        return null
    }

    /** Taruh item di slot kosong pertama; tambah halaman baru kalau perlu. False kalau sudah penuh. */
    fun addAtFree(item: HomeItem, startPage: Int = 0): Boolean {
        item.spanW = item.spanW.coerceIn(1, cols)
        item.spanH = item.spanH.coerceIn(1, rows)
        var slot = findFreeSlot(item.spanW, item.spanH, startPage)
        if (slot == null && pageCount < MAX_PAGES) {
            pageCount += 1
            slot = findFreeSlot(item.spanW, item.spanH, pageCount - 1)
        }
        if (slot == null) return false
        item.page = slot[0]
        item.row = slot[1]
        item.col = slot[2]
        items.add(item)
        return true
    }

    /** Rapikan semua item setelah ukuran grid berubah; yang tidak muat dipindah ke slot kosong. */
    fun normalizeToGrid() {
        val maxPage = items.maxOfOrNull { it.page } ?: 0
        if (pageCount < maxPage + 1) pageCount = maxPage + 1
        val sorted = items.sortedWith(compareBy({ it.page }, { it.row }, { it.col }))
        items.clear()
        for (o in sorted) {
            o.spanW = o.spanW.coerceIn(1, cols)
            o.spanH = o.spanH.coerceIn(1, rows)
            if (o.col + o.spanW > cols) o.col = cols - o.spanW
            if (o.row + o.spanH > rows) o.row = rows - o.spanH
            if (o.col < 0) o.col = 0
            if (o.row < 0) o.row = 0
            if (fits(o.page, o.row, o.col, o.spanW, o.spanH, null)) {
                items.add(o)
            } else {
                addAtFree(o, o.page)
            }
        }
    }

    /** Ganti [old] dengan [new] di tempat yang sama (halaman/baris/kolom, atau urutan dock). */
    fun replaceItem(old: HomeItem, new: HomeItem) {
        val i = items.indexOf(old)
        if (i >= 0) {
            new.page = old.page
            new.row = old.row
            new.col = old.col
            items[i] = new
            return
        }
        val d = dock.indexOf(old)
        if (d >= 0) dock[d] = new
    }

    fun removeItem(o: HomeItem) {
        items.remove(o)
        dock.remove(o)
    }

    /** Buat folder baru di tempat [target], berisi aplikasi target dan [dragComp]. */
    fun makeFolder(target: HomeItem, dragComp: String, name: String): HomeItem {
        val f = HomeItem(newId(), ItemType.FOLDER)
        f.name = name
        f.children.add(target.component)
        if (dragComp != target.component) f.children.add(dragComp)
        replaceItem(target, f)
        return f
    }

    /** Folder kosong dihapus, folder berisi satu aplikasi dibubarkan jadi ikon biasa. */
    fun cleanFolder(f: HomeItem) {
        if (f.type != ItemType.FOLDER) return
        if (f.children.isEmpty()) {
            removeItem(f)
        } else if (f.children.size == 1) {
            val a = HomeItem(newId(), ItemType.APP)
            a.component = f.children[0]
            replaceItem(f, a)
        }
    }

    /** Hapus halaman kosong (minimal tersisa satu halaman) dan geser nomor halaman sisanya. */
    fun trimPages() {
        val used = items.map { it.page }.distinct().sorted()
        val keep = if (used.isEmpty()) listOf(0) else used
        val map = HashMap<Int, Int>()
        for ((index, p) in keep.withIndex()) map[p] = index
        for (o in items) o.page = map[o.page] ?: 0
        pageCount = keep.size
    }

    /** Apakah aplikasi sudah ada di layar utama, dock, atau salah satu folder. */
    fun isPlaced(key: String): Boolean {
        for (o in items) {
            if (o.type == ItemType.APP && o.component == key) return true
            if (o.type == ItemType.FOLDER && key in o.children) return true
        }
        for (o in dock) {
            if (o.type == ItemType.APP && o.component == key) return true
            if (o.type == ItemType.FOLDER && key in o.children) return true
        }
        return false
    }

    /** Buang aplikasi yang tidak ada lagi di [valid]. True kalau ada yang berubah. */
    fun removeMissing(valid: Set<String>): Boolean {
        var changed = false
        for (o in items.toList()) {
            if (prune(o, valid)) changed = true
        }
        for (o in dock.toList()) {
            if (prune(o, valid)) changed = true
        }
        return changed
    }

    private fun prune(o: HomeItem, valid: Set<String>): Boolean {
        if (o.type == ItemType.APP) {
            if (o.component !in valid) {
                removeItem(o)
                return true
            }
        } else if (o.type == ItemType.FOLDER) {
            val before = o.children.size
            o.children.retainAll(valid)
            if (o.children.size != before) {
                cleanFolder(o)
                return true
            }
        }
        return false
    }

    /** Buang semua jejak sebuah aplikasi (mis. saat disembunyikan). */
    fun removeApp(key: String): Boolean = removeMissing(allExcept(key))

    private fun allExcept(key: String): Set<String> {
        val all = HashSet<String>()
        for (o in items) {
            if (o.type == ItemType.APP) all.add(o.component)
            if (o.type == ItemType.FOLDER) all.addAll(o.children)
        }
        for (o in dock) {
            if (o.type == ItemType.APP) all.add(o.component)
            if (o.type == ItemType.FOLDER) all.addAll(o.children)
        }
        all.remove(key)
        return all
    }
}
