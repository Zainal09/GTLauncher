package com.gtlauncher.app

import android.app.AlertDialog
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.SparseArray
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * Otak layar utama: halaman-halaman, dock, mode edit, seret-dan-lepas, folder, dan widget.
 * Data tata letak ada di [HomeLayout]; kelas ini menggambarnya dan mengubahnya.
 */
class HomeController(
    private val act: MainActivity,
    private val cfg: LauncherPrefs,
    private val dragLayer: DragLayer,
    private val pager: ViewPager2,
    private val dots: LinearLayout,
    private val dockView: LinearLayout,
    private val editBar: View
) : DragLayer.Host {

    private companion object {
        const val SRC_HOME = 1
        const val SRC_DOCK = 2
        const val SRC_FOLDER = 3

        const val T_NONE = 0
        const val T_CELL = 1
        const val T_MERGE = 2
        const val T_DOCK = 3
        const val T_DOCK_MERGE = 4

        const val EDGE_DWELL_MS = 600L
    }

    private class Target {
        var type = T_NONE
        var row = 0
        var col = 0
        var index = 0
        var item: HomeItem? = null
        var rl = 0f
        var rt = 0f
        var rw = 0f
        var rh = 0f
    }

    private val layout = HomeLayout()
    private val ui = Handler(Looper.getMainLooper())
    private val pageViews = SparseArray<HomePageView>()
    private var appMap: Map<String, AppInfo> = emptyMap()
    private var freshSet: Set<String> = cfg.fresh
    private var needInit = false
    private var editing = false

    // ---- seret
    private var dragItem: HomeItem? = null
    private var dragSrc = 0
    private var originCell: CellView? = null
    private var ghost: ImageView? = null
    private var hint: View? = null
    private var ghostW = 0
    private var ghostH = 0
    private var offX = 0f
    private var offY = 0f
    private var originLeft = 0
    private var originTop = 0
    private var dragRawX = 0f
    private var dragRawY = 0f
    private var target = Target()
    private var edgeSide = 0
    private val flipRunnable = Runnable { doFlip() }
    private val tmpRect = Rect()

    // ---- folder
    private var folderScrim: FrameLayout? = null
    private var folderItem: HomeItem? = null
    private var folderName: EditText? = null

    private inner class PageHolder(val page: HomePageView) : RecyclerView.ViewHolder(page)

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount(): Int = layout.pageCount

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val v = HomePageView(act)
            v.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            v.setOnClickListener { if (editing) exitEdit() }
            v.setOnLongClickListener {
                onEmptyLongPress()
                true
            }
            return PageHolder(v)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.page.pageIndex = position
            pageViews.put(position, holder.page)
            populate(holder.page, position)
        }

        override fun onViewRecycled(holder: PageHolder) {
            val i = holder.page.pageIndex
            if (pageViews.get(i) === holder.page) pageViews.remove(i)
            super.onViewRecycled(holder)
        }
    }

    private val pagerAdapter = PageAdapter()

    init {
        layout.cols = cfg.gridCols
        layout.rows = cfg.gridRows
        val json = cfg.layoutJson
        val ok = json != null && HomeStore.fromJson(json, layout)
        if (ok) {
            layout.normalizeToGrid()
        } else {
            layout.items.clear()
            layout.dock.clear()
            layout.pageCount = 1
            needInit = true
        }
        dragLayer.host = this
        pager.adapter = pagerAdapter
        pager.offscreenPageLimit = 1
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots()
                if (dragItem != null) ui.post { updateTarget() }
            }
        })
        updateDots()
    }

    // ================================================================ util

    private fun dp(v: Int): Int = act.dpToPx(v)

    private fun save() {
        cfg.layoutJson = HomeStore.toJson(layout)
    }

    private fun dockMax(): Int = Math.min(5, Math.max(4, layout.cols))

    private fun homeIconPx(): Int {
        val w = act.resources.displayMetrics.widthPixels
        val cellW = (w - 2f * dp(16)) / layout.cols
        return Math.min(dp(cfg.iconSizeDp).toFloat(), cellW * 0.8f).toInt()
    }

    private fun dockIconPx(): Int {
        val w = act.resources.displayMetrics.widthPixels
        val slot = (w - dp(48)).toFloat() / dockMax()
        return Math.min(dp(cfg.iconSizeDp).toFloat(), slot * 0.8f).toInt()
    }

    private fun approxCellW(): Float {
        val w = if (pager.width > 0) pager.width else act.resources.displayMetrics.widthPixels
        return (w - 2f * dp(16)) / layout.cols
    }

    private fun approxCellH(): Float {
        val h = if (pager.height > 0) {
            pager.height
        } else {
            (act.resources.displayMetrics.heightPixels * 0.68f).toInt()
        }
        return h.toFloat() / layout.rows
    }

    /** Ukuran widget dalam sel (lebar, tinggi), dari ukuran yang diminta widget. */
    fun spanFor(info: AppWidgetProviderInfo): IntArray {
        var w = Math.ceil((info.minWidth / approxCellW()).toDouble()).toInt()
        var h = Math.ceil((info.minHeight / approxCellH()).toDouble()).toInt()
        if (Build.VERSION.SDK_INT >= 31) {
            if (info.targetCellWidth > 0) w = info.targetCellWidth
            if (info.targetCellHeight > 0) h = info.targetCellHeight
        }
        return intArrayOf(w.coerceIn(1, layout.cols), h.coerceIn(1, layout.rows))
    }

    private fun forEachCell(f: (CellView) -> Unit) {
        for (i in 0 until pageViews.size()) {
            val pv = pageViews.valueAt(i)
            for (j in 0 until pv.childCount) {
                val c = pv.getChildAt(j) as? CellView
                if (c != null) f(c)
            }
        }
        for (j in 0 until dockView.childCount) {
            val c = dockView.getChildAt(j) as? CellView
            if (c != null) f(c)
        }
    }

    // ================================================================ data aplikasi

    /** Dipanggil tiap daftar aplikasi berubah. [rawKeys] = semua aplikasi terpasang (termasuk yang disembunyikan). */
    fun setApps(visible: List<AppInfo>, rawKeys: Set<String>) {
        appMap = visible.associateBy { it.key }
        if (needInit && visible.isNotEmpty()) {
            buildInitial(visible)
            needInit = false
        }
        if (visible.isNotEmpty()) {
            var changed = layout.removeMissing(appMap.keys)
            if (pruneWidgets()) changed = true
            if (handleNewApps(visible, rawKeys)) changed = true
            if (changed) save()
        }
        refreshAll()
        renderDock()
    }

    private fun pruneWidgets(): Boolean {
        var changed = false
        for (o in layout.items.toList()) {
            if (o.type == ItemType.WIDGET && act.widgetInfo(o.widgetId) == null) {
                act.deleteWidget(o.widgetId)
                layout.removeItem(o)
                changed = true
            }
        }
        return changed
    }

    private fun handleNewApps(visible: List<AppInfo>, rawKeys: Set<String>): Boolean {
        val known = cfg.known
        if (known.isEmpty()) {
            // Pertama kali: semua aplikasi yang sudah ada dianggap lama
            cfg.known = rawKeys
            return false
        }
        val fresh = cfg.fresh.toMutableSet()
        var changed = false
        if (cfg.autoAddNew) {
            for (a in visible) {
                if (a.key in known || layout.isPlaced(a.key)) continue
                val item = HomeItem(layout.newId(), ItemType.APP)
                item.component = a.key
                if (layout.addAtFree(item, 0)) {
                    fresh.add(a.key)
                    changed = true
                }
            }
        }
        cfg.known = rawKeys
        fresh.retainAll(rawKeys)
        cfg.fresh = fresh
        freshSet = fresh
        return changed
    }

    fun onAppLaunched(key: String) {
        if (key in freshSet) {
            val f = freshSet.toMutableSet()
            f.remove(key)
            freshSet = f
            cfg.fresh = f
            forEachCell { if (it.item.component == key) it.setFresh(false) }
        }
    }

    // ================================================================ tata letak awal

    private fun buildInitial(visible: List<AppInfo>) {
        // Widget dari versi lama: lebar penuh
        val legacy = mutableListOf<HomeItem>()
        for (id in cfg.widgetIds) {
            val info = act.widgetInfo(id)
            if (info == null) {
                act.deleteWidget(id)
                continue
            }
            val o = HomeItem(layout.newId(), ItemType.WIDGET)
            o.widgetId = id
            val span = spanFor(info)
            o.spanW = layout.cols
            o.spanH = span[1]
            legacy.add(o)
        }
        cfg.widgetIds = emptyList()
        buildDefault(legacy, visible)
    }

    private fun buildDefault(widgets: List<HomeItem>, visible: List<AppInfo>) {
        layout.items.clear()
        layout.dock.clear()
        layout.pageCount = 1
        if (cfg.clockVisible) addClock(0)
        for (w in widgets) {
            if (!layout.addAtFree(w, 0)) act.deleteWidget(w.widgetId)
        }
        val homePkgs = cfg.getList("home") ?: act.defaultHomePackages()
        for (pkg in homePkgs) {
            val a = visible.firstOrNull { it.packageName == pkg } ?: continue
            if (layout.isPlaced(a.key)) continue
            val o = HomeItem(layout.newId(), ItemType.APP)
            o.component = a.key
            layout.addAtFree(o, 0)
        }
        val dockPkgs = cfg.getList("dock") ?: act.defaultDockPackages()
        for (pkg in dockPkgs) {
            if (layout.dock.size >= dockMax()) break
            val a = visible.firstOrNull { it.packageName == pkg } ?: continue
            if (layout.isPlaced(a.key)) continue
            val o = HomeItem(layout.newId(), ItemType.APP)
            o.component = a.key
            layout.dock.add(o)
        }
        save()
    }

    fun resetToDefault() {
        val widgets = layout.items.filter { it.type == ItemType.WIDGET }
        cfg.resetLayout()
        cfg.fresh = emptySet()
        freshSet = emptySet()
        buildDefault(widgets, appMap.values.toList())
        pagerAdapter.notifyDataSetChanged()
        pager.setCurrentItem(0, false)
        updateDots()
        refreshAll()
        renderDock()
    }

    // ================================================================ gambar halaman dan dock

    fun refreshAll() {
        for (i in 0 until pageViews.size()) {
            val pv = pageViews.valueAt(i)
            if (pv.pageIndex in 0 until layout.pageCount) populate(pv, pv.pageIndex)
        }
    }

    private fun populate(pv: HomePageView, p: Int) {
        pv.removeAllViews()
        pv.cols = layout.cols
        pv.rows = layout.rows
        for (o in layout.itemsOnPage(p)) {
            val c = makeCell(o, false) ?: continue
            if (o === dragItem && dragSrc != SRC_FOLDER) {
                c.visibility = View.INVISIBLE
                originCell = c
            }
            pv.addView(c)
        }
    }

    fun renderDock() {
        dockView.removeAllViews()
        for (o in layout.dock.toList()) {
            val c = makeCell(o, true) ?: continue
            if (o === dragItem && dragSrc == SRC_DOCK) {
                c.visibility = View.INVISIBLE
                originCell = c
            }
            dockView.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val fill = Color.argb(LauncherPrefs.GLASS_ALPHAS[cfg.glassIndex], 255, 255, 255)
        (dockView.background?.mutate() as? GradientDrawable)?.setColor(fill)
    }

    /** Terapkan ulang pengaturan tampilan (ukuran ikon, nama, warna, kaca, jam). */
    fun applyStyle() {
        refreshAll()
        renderDock()
    }

    private fun iconLp(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )

    private fun iconContent(icon: Drawable, label: String, inDock: Boolean): View {
        val px = if (inDock) dockIconPx() else homeIconPx()
        val ll = LinearLayout(act)
        ll.orientation = LinearLayout.VERTICAL
        ll.gravity = Gravity.CENTER_HORIZONTAL
        val iv = ImageView(act)
        iv.setImageDrawable(icon)
        ll.addView(iv, LinearLayout.LayoutParams(px, px))
        if (!inDock && cfg.showLabels) {
            val tv = TextView(act)
            tv.text = label
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            tv.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            tv.maxLines = 1
            tv.ellipsize = android.text.TextUtils.TruncateAt.END
            tv.gravity = Gravity.CENTER
            val color = LauncherPrefs.LABEL_COLORS[cfg.labelColorIndex]
            tv.setTextColor(color)
            if (color == Color.WHITE) tv.setShadowLayer(4f, 0f, 0f, 0x80000000.toInt())
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(5)
            ll.addView(tv, lp)
        }
        return ll
    }

    private fun makeCell(o: HomeItem, inDock: Boolean): CellView? {
        val cell = CellView(act, o)
        when (o.type) {
            ItemType.APP -> {
                val app = appMap[o.component] ?: return null
                cell.setContent(iconContent(app.newIcon(act.resources), app.label, inDock), iconLp())
                if (!inDock && o.component in freshSet) cell.setFresh(true)
            }
            ItemType.FOLDER -> {
                val bmps = ArrayList<Bitmap>()
                for (k in o.children) {
                    val b = appMap[k]?.bitmap
                    if (b != null) bmps.add(b)
                }
                val name = if (o.name.isBlank()) "Folder" else o.name
                cell.setContent(iconContent(FolderDrawable(bmps), name, inDock), iconLp())
            }
            ItemType.WIDGET -> {
                val hv = act.widgetView(o) ?: return null
                (hv.parent as? ViewGroup)?.removeView(hv)
                hv.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(22).toFloat())
                    }
                }
                hv.clipToOutline = true
                val m = dp(4)
                val lp = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                lp.setMargins(m, m, m, m)
                cell.setContent(hv, lp)
                cell.sizeListener = { w, h -> act.updateWidgetSize(hv, w - 2 * m, h - 2 * m) }
            }
            ItemType.CLOCK -> {
                val cv = ClockView(act)
                cv.applyPrefs(cfg)
                val m = dp(4)
                val lp = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                lp.setMargins(m, m, m, m)
                cell.setContent(cv, lp)
            }
            else -> return null
        }
        cell.setEditing(editing)
        cell.setOnClickListener { onCellClick(cell) }
        cell.onLongPress = { onCellLongPress(cell) }
        cell.onBadge = { requestRemove(cell.item) }
        return cell
    }

    // ================================================================ halaman dan titik

    private fun updateDots() {
        dots.removeAllViews()
        val n = layout.pageCount
        val cur = pager.currentItem
        for (i in 0 until n) {
            val v = View(act)
            val g = GradientDrawable()
            g.shape = GradientDrawable.OVAL
            g.setColor(if (i == cur) 0xFFFFFFFF.toInt() else 0x66FFFFFF)
            v.background = g
            val lp = LinearLayout.LayoutParams(dp(7), dp(7))
            lp.setMargins(dp(4), 0, dp(4), 0)
            dots.addView(v, lp)
        }
    }

    // ================================================================ ketuk dan tahan

    private fun onCellClick(cell: CellView) {
        val o = cell.item
        when (o.type) {
            ItemType.APP -> {
                if (!editing) {
                    val app = appMap[o.component]
                    if (app != null) act.launch(app, cell)
                }
            }
            ItemType.FOLDER -> openFolder(o)
            ItemType.WIDGET -> if (editing) showWidgetOptions(o)
            ItemType.CLOCK -> if (editing) showWidgetOptions(o) else act.openClockApp()
        }
    }

    private fun onCellLongPress(cell: CellView) {
        val o = cell.item
        if (cfg.layoutLocked) {
            val app = appMap[o.component]
            if (o.type == ItemType.APP && app != null) act.showAppMenu(app, true) else act.showHomeMenu()
            return
        }
        if (dragItem != null) return
        if (!editing) enterEdit()
        val snap = cell.snapshotClean() ?: return
        val loc = IntArray(2)
        cell.getLocationOnScreen(loc)
        val src = if (layout.dock.contains(o)) SRC_DOCK else SRC_HOME
        beginDrag(o, src, cell, snap, loc[0], loc[1])
    }

    private fun onEmptyLongPress() {
        if (editing || dragItem != null) return
        if (cfg.layoutLocked) act.showHomeMenu() else enterEdit()
    }

    // ================================================================ mode edit

    fun isEditing(): Boolean = editing

    fun enterEdit() {
        if (editing) return
        if (cfg.layoutLocked) {
            act.toast("Tata letak terkunci")
            return
        }
        editing = true
        editBar.visibility = View.VISIBLE
        forEachCell { it.setEditing(true) }
    }

    fun exitEdit() {
        if (!editing) return
        closeFolder()
        editing = false
        editBar.visibility = View.GONE
        forEachCell { it.setEditing(false) }
        layout.trimPages()
        pagerAdapter.notifyDataSetChanged()
        val cur = Math.min(pager.currentItem, layout.pageCount - 1)
        pager.setCurrentItem(cur, false)
        updateDots()
        save()
    }

    private fun requestRemove(o: HomeItem) {
        if (o.type == ItemType.FOLDER && o.children.isNotEmpty()) {
            AlertDialog.Builder(act)
                .setTitle("Hapus folder?")
                .setMessage("Aplikasi di dalamnya tetap ada di Pustaka Aplikasi.")
                .setPositiveButton("Hapus") { _, _ -> doRemove(o) }
                .setNegativeButton("Batal", null)
                .show()
        } else {
            doRemove(o)
        }
    }

    private fun doRemove(o: HomeItem) {
        if (o.type == ItemType.WIDGET) act.deleteWidget(o.widgetId)
        if (o.type == ItemType.CLOCK) cfg.clockVisible = false
        layout.removeItem(o)
        save()
        refreshAll()
        renderDock()
    }

    fun removeApp(key: String) {
        if (layout.removeApp(key)) {
            save()
            refreshAll()
            renderDock()
        }
    }

    /** 0 = berhasil, 1 = sudah ada, 2 = penuh. */
    fun addAppToHome(app: AppInfo): Int {
        if (layout.isPlaced(app.key)) return 1
        val o = HomeItem(layout.newId(), ItemType.APP)
        o.component = app.key
        if (!layout.addAtFree(o, pager.currentItem)) return 2
        save()
        pagerAdapter.notifyDataSetChanged()
        updateDots()
        refreshAll()
        return 0
    }

    /** 0 = berhasil, 1 = sudah ada, 2 = penuh. */
    fun addAppToDock(app: AppInfo): Int {
        if (layout.isPlaced(app.key)) return 1
        if (layout.dock.size >= dockMax()) return 2
        val o = HomeItem(layout.newId(), ItemType.APP)
        o.component = app.key
        layout.dock.add(o)
        save()
        renderDock()
        return 0
    }

    // ================================================================ grid, jam, widget

    fun gridCols(): Int = layout.cols

    fun gridRows(): Int = layout.rows

    fun setGrid(cols: Int, rows: Int) {
        layout.cols = cols
        layout.rows = rows
        layout.normalizeToGrid()
        val max = dockMax()
        while (layout.dock.size > max) {
            val moved = layout.dock.removeAt(layout.dock.size - 1)
            moved.spanW = 1
            moved.spanH = 1
            layout.addAtFree(moved, 0)
        }
        save()
        pagerAdapter.notifyDataSetChanged()
        updateDots()
        renderDock()
    }

    fun hasClock(): Boolean = layout.items.any { it.type == ItemType.CLOCK }

    private fun addClock(startPage: Int): Boolean {
        val c = HomeItem(layout.newId(), ItemType.CLOCK)
        c.spanW = layout.cols
        c.spanH = 2
        if (layout.addAtFree(c, startPage)) return true
        c.spanW = Math.min(2, layout.cols)
        c.spanH = 1
        return layout.addAtFree(c, startPage)
    }

    /** Dipakai pemilih widget dan pengaturan jam. */
    fun addClockWidget(): Boolean {
        cfg.clockVisible = true
        if (hasClock()) return true
        val ok = addClock(pager.currentItem)
        if (ok) {
            save()
            pagerAdapter.notifyDataSetChanged()
            updateDots()
            refreshAll()
        } else {
            cfg.clockVisible = false
        }
        return ok
    }

    fun setClockVisible(b: Boolean) {
        if (b) {
            if (!addClockWidget()) act.toast("Tidak ada ruang kosong")
            return
        }
        cfg.clockVisible = false
        val existing = layout.items.firstOrNull { it.type == ItemType.CLOCK }
        if (existing != null) {
            layout.removeItem(existing)
            save()
            refreshAll()
        }
    }

    fun addWidget(id: Int, info: AppWidgetProviderInfo): Boolean {
        val span = spanFor(info)
        val o = HomeItem(layout.newId(), ItemType.WIDGET)
        o.widgetId = id
        o.spanW = span[0]
        o.spanH = span[1]
        if (!layout.addAtFree(o, pager.currentItem)) return false
        save()
        pagerAdapter.notifyDataSetChanged()
        updateDots()
        refreshAll()
        pager.setCurrentItem(o.page, false)
        return true
    }

    private fun showWidgetOptions(o: HomeItem) {
        val items = arrayOf("Ubah ukuran", "Hapus")
        AlertDialog.Builder(act)
            .setItems(items) { _, which ->
                if (which == 0) showResize(o) else doRemove(o)
            }
            .show()
    }

    private fun stepRow(
        title: String,
        value: TextView,
        enabled: Boolean,
        onMinus: () -> Unit,
        onPlus: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val label = TextView(act)
        label.text = title
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val minus = Button(act)
        minus.text = "−"
        minus.isEnabled = enabled
        minus.setOnClickListener { onMinus() }
        row.addView(minus)
        value.gravity = Gravity.CENTER
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        row.addView(value, LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT))
        val plus = Button(act)
        plus.text = "+"
        plus.isEnabled = enabled
        plus.setOnClickListener { onPlus() }
        row.addView(plus)
        return row
    }

    private fun showResize(o: HomeItem) {
        val info = if (o.type == ItemType.WIDGET) act.widgetInfo(o.widgetId) else null
        var canW = true
        var canH = true
        var minW = 1
        var minH = 1
        if (o.type == ItemType.CLOCK) {
            minW = Math.min(2, layout.cols)
        }
        if (info != null) {
            val mode = info.resizeMode
            canW = (mode and AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0
            canH = (mode and AppWidgetProviderInfo.RESIZE_VERTICAL) != 0
            if (info.minResizeWidth > 0) {
                minW = Math.ceil((info.minResizeWidth / approxCellW()).toDouble()).toInt()
            }
            if (info.minResizeHeight > 0) {
                minH = Math.ceil((info.minResizeHeight / approxCellH()).toDouble()).toInt()
            }
        }
        minW = Math.max(1, Math.min(minW, Math.min(o.spanW, layout.cols)))
        minH = Math.max(1, Math.min(minH, Math.min(o.spanH, layout.rows)))

        val wText = TextView(act)
        val hText = TextView(act)
        fun refreshText() {
            wText.text = o.spanW.toString()
            hText.text = o.spanH.toString()
        }
        fun change(dw: Int, dh: Int) {
            val nw = (o.spanW + dw).coerceIn(minW, layout.cols)
            val nh = (o.spanH + dh).coerceIn(minH, layout.rows)
            if (nw == o.spanW && nh == o.spanH) return
            if (!layout.fits(o.page, o.row, o.col, nw, nh, o)) {
                act.toast("Tidak muat di sini")
                return
            }
            o.spanW = nw
            o.spanH = nh
            save()
            refreshAll()
            refreshText()
        }

        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(12), dp(24), dp(4))
        box.addView(stepRow("Lebar (kolom)", wText, canW, { change(-1, 0) }, { change(1, 0) }))
        box.addView(stepRow("Tinggi (baris)", hText, canH, { change(0, -1) }, { change(0, 1) }))
        refreshText()
        AlertDialog.Builder(act)
            .setTitle("Ukuran widget")
            .setView(box)
            .setPositiveButton("Selesai", null)
            .show()
    }

    // ================================================================ folder

    private fun openFolder(f: HomeItem) {
        closeFolder()
        folderItem = f

        val scrim = FrameLayout(act)
        scrim.setBackgroundColor(0x80000000.toInt())
        scrim.isClickable = true
        scrim.setOnClickListener { closeFolder() }

        val panel = LinearLayout(act)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundResource(R.drawable.bg_folder_panel)
        panel.setPadding(dp(16), dp(16), dp(16), dp(12))
        panel.isClickable = true

        val name = EditText(act)
        name.setText(if (f.name.isBlank()) "Folder" else f.name)
        name.setTextColor(Color.WHITE)
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        name.gravity = Gravity.CENTER
        name.background = null
        name.maxLines = 1
        name.setSingleLine(true)
        name.imeOptions = EditorInfo.IME_ACTION_DONE
        name.isEnabled = !cfg.layoutLocked
        name.setOnEditorActionListener { v, _, _ ->
            commitFolderName()
            hideKeyboard(v)
            v.clearFocus()
            true
        }
        panel.addView(
            name,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val rv = RecyclerView(act)
        rv.layoutManager = GridLayoutManager(act, 3)
        val adapter = AppAdapter(
            act.resources,
            { app, view ->
                act.launch(app, view)
                closeFolder()
            },
            { app, view -> onFolderChildLongPress(f, app, view) }
        )
        adapter.applyStyle(dp(cfg.iconSizeDp), true, Color.WHITE)
        adapter.submit(f.children.mapNotNull { appMap[it] })
        rv.adapter = adapter
        val rvLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        rvLp.topMargin = dp(8)
        panel.addView(rv, rvLp)

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        lp.setMargins(dp(28), dp(28), dp(28), dp(28))
        scrim.addView(panel, lp)
        dragLayer.addView(
            scrim,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        folderScrim = scrim
        folderName = name
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(120).start()
    }

    private fun commitFolderName() {
        val f = folderItem ?: return
        val n = folderName ?: return
        if (cfg.layoutLocked) return
        val t = n.text.toString().trim()
        if (t.isNotEmpty() && t != f.name) {
            f.name = t
            save()
            refreshAll()
            renderDock()
        }
    }

    fun closeFolder() {
        val s = folderScrim ?: return
        commitFolderName()
        val n = folderName
        if (n != null) hideKeyboard(n)
        dragLayer.removeView(s)
        folderScrim = null
        folderItem = null
        folderName = null
    }

    private fun hideKeyboard(v: View) {
        val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun onFolderChildLongPress(f: HomeItem, app: AppInfo, view: View) {
        if (cfg.layoutLocked || dragItem != null) return
        val snap = view.snapshot() ?: return
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        closeFolder()
        f.children.remove(app.key)
        layout.cleanFolder(f)
        val o = HomeItem(layout.newId(), ItemType.APP)
        o.component = app.key
        if (!editing) enterEdit()
        save()
        refreshAll()
        renderDock()
        beginDrag(o, SRC_FOLDER, null, snap, loc[0], loc[1])
    }

    // ================================================================ seret dan lepas

    fun isBusy(): Boolean = dragItem != null || folderScrim != null

    private fun beginDrag(o: HomeItem, src: Int, cell: CellView?, bmp: Bitmap, left: Int, top: Int) {
        dragItem = o
        dragSrc = src
        originCell = cell
        ghostW = bmp.width
        ghostH = bmp.height
        dragRawX = dragLayer.lastRawX
        dragRawY = dragLayer.lastRawY
        offX = dragRawX - left
        offY = dragRawY - top
        originLeft = left
        originTop = top
        target = Target()

        val h = View(act)
        h.setBackgroundResource(R.drawable.bg_drop_hint)
        h.visibility = View.GONE
        dragLayer.addView(h, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.TOP or Gravity.START))
        hint = h

        val g = ImageView(act)
        g.setImageBitmap(bmp)
        g.scaleType = ImageView.ScaleType.FIT_XY
        dragLayer.addView(g, FrameLayout.LayoutParams(ghostW, ghostH, Gravity.TOP or Gravity.START))
        ghost = g
        placeGhost()
        g.scaleX = 1.08f
        g.scaleY = 1.08f
        g.alpha = 0.92f

        cell?.visibility = View.INVISIBLE
        dragLayer.dragActive = true
        dragLayer.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        updateTarget()
    }

    override fun onDragTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                dragRawX = ev.rawX
                dragRawY = ev.rawY
                placeGhost()
                updateTarget()
                checkEdge(ev.rawX)
            }
            MotionEvent.ACTION_UP -> {
                dragRawX = ev.rawX
                dragRawY = ev.rawY
                placeGhost()
                updateTarget()
                endDrag(true)
            }
            MotionEvent.ACTION_CANCEL -> endDrag(false)
            else -> {}
        }
    }

    private fun placeGhost() {
        val g = ghost ?: return
        val dl = IntArray(2)
        dragLayer.getLocationOnScreen(dl)
        g.translationX = dragRawX - offX - dl[0]
        g.translationY = dragRawY - offY - dl[1]
    }

    private fun checkEdge(rawX: Float) {
        val dl = IntArray(2)
        dragLayer.getLocationOnScreen(dl)
        val side = if (rawX < dl[0] + dp(36)) {
            -1
        } else if (rawX > dl[0] + dragLayer.width - dp(36)) {
            1
        } else {
            0
        }
        if (side != edgeSide) {
            ui.removeCallbacks(flipRunnable)
            edgeSide = side
            if (side != 0) ui.postDelayed(flipRunnable, EDGE_DWELL_MS)
        }
    }

    private fun doFlip() {
        if (dragItem == null) return
        val cur = pager.currentItem
        if (edgeSide < 0 && cur > 0) {
            pager.setCurrentItem(cur - 1, true)
        } else if (edgeSide > 0) {
            if (cur < layout.pageCount - 1) {
                pager.setCurrentItem(cur + 1, true)
            } else if (layout.pageCount < HomeLayout.MAX_PAGES) {
                layout.pageCount += 1
                pagerAdapter.notifyItemInserted(layout.pageCount - 1)
                updateDots()
                pager.setCurrentItem(layout.pageCount - 1, true)
            }
        }
        if (edgeSide != 0) ui.postDelayed(flipRunnable, 800L)
    }

    private fun updateTarget() {
        val o = dragItem ?: return
        val gl = dragRawX - offX
        val gt = dragRawY - offY
        val cx = gl + ghostW / 2f
        val cy = gt + ghostH / 2f
        val t = Target()

        var inDock = false
        if (o.isIcon && dockView.getGlobalVisibleRect(tmpRect)) {
            val zone = Rect(tmpRect)
            zone.top -= dp(24)
            inDock = zone.contains(cx.toInt(), cy.toInt())
        }
        if (inDock) {
            dockView.getGlobalVisibleRect(tmpRect)
            computeDock(o, t, tmpRect, cx)
        } else {
            computePage(o, t, gl, gt, cx, cy)
        }
        applyTarget(t)
    }

    private fun computeDock(o: HomeItem, t: Target, r: Rect, cx: Float) {
        val list = layout.dock
        val n = list.size
        val innerLeft = (r.left + dockView.paddingLeft).toFloat()
        val innerW = Math.max(1f, (r.width() - dockView.paddingLeft - dockView.paddingRight).toFloat())
        val slotTop = (r.top + dockView.paddingTop).toFloat()
        val slotH = Math.max(1f, (r.height() - dockView.paddingTop - dockView.paddingBottom).toFloat())

        if (n > 0) {
            val dispW = innerW / n
            val pos = (cx - innerLeft) / dispW
            val di = Math.floor(pos.toDouble()).toInt()
            if (di in 0 until n) {
                val tgt = list[di]
                val frac = pos - di
                if (o.type == ItemType.APP && tgt !== o && tgt.isIcon && frac >= 0.3f && frac <= 0.7f) {
                    t.type = T_DOCK_MERGE
                    t.item = tgt
                    t.rl = innerLeft + di * dispW
                    t.rt = slotTop
                    t.rw = dispW
                    t.rh = slotH
                    return
                }
            }
        }
        val fromDock = list.contains(o)
        if (!fromDock && n >= dockMax()) return
        val slots = if (fromDock) n else n + 1
        val sw = innerW / slots
        val idx = Math.floor(((cx - innerLeft) / sw).toDouble()).toInt().coerceIn(0, slots - 1)
        t.type = T_DOCK
        t.index = idx
        t.rl = innerLeft + idx * sw
        t.rt = slotTop
        t.rw = sw
        t.rh = slotH
    }

    private fun computePage(o: HomeItem, t: Target, gl: Float, gt: Float, cx: Float, cy: Float) {
        val page = pager.currentItem
        val pv = pageViews.get(page) ?: return
        val cw = pv.cellW()
        val ch = pv.cellH()
        if (cw <= 0f || ch <= 0f) return
        val pl = IntArray(2)
        pv.getLocationOnScreen(pl)
        val originX = pl[0] + pv.padPx.toFloat()
        val originY = pl[1].toFloat()

        if (o.spanW == 1 && o.spanH == 1) {
            val c = Math.floor(((cx - originX) / cw).toDouble()).toInt()
            val r = Math.floor(((cy - originY) / ch).toDouble()).toInt()
            if (r < 0 || r >= layout.rows || c < 0 || c >= layout.cols) return
            val occ = layout.itemAt(page, r, c, o)
            if (occ == null) {
                t.type = T_CELL
                t.row = r
                t.col = c
                t.rl = originX + c * cw
                t.rt = originY + r * ch
                t.rw = cw
                t.rh = ch
            } else if (o.type == ItemType.APP && occ.isIcon) {
                val ccx = originX + (c + 0.5f) * cw
                val ccy = originY + (r + 0.5f) * ch
                if (Math.abs(cx - ccx) < cw * 0.3f && Math.abs(cy - ccy) < ch * 0.3f) {
                    t.type = T_MERGE
                    t.item = occ
                    t.rl = originX + c * cw
                    t.rt = originY + r * ch
                    t.rw = cw
                    t.rh = ch
                }
            }
        } else {
            val maxC = Math.max(0, layout.cols - o.spanW)
            val maxR = Math.max(0, layout.rows - o.spanH)
            val c = Math.round((gl - originX) / cw).coerceIn(0, maxC)
            val r = Math.round((gt - originY) / ch).coerceIn(0, maxR)
            if (layout.fits(page, r, c, o.spanW, o.spanH, o)) {
                t.type = T_CELL
                t.row = r
                t.col = c
                t.rl = originX + c * cw
                t.rt = originY + r * ch
                t.rw = cw * o.spanW
                t.rh = ch * o.spanH
            }
        }
    }

    private fun sameTarget(a: Target, b: Target): Boolean =
        a.type == b.type && a.row == b.row && a.col == b.col && a.index == b.index && a.item === b.item

    private fun applyTarget(t: Target) {
        if (sameTarget(t, target)) return
        target = t
        val h = hint ?: return
        if (t.type == T_NONE) {
            h.visibility = View.GONE
            return
        }
        val dl = IntArray(2)
        dragLayer.getLocationOnScreen(dl)
        val lp = h.layoutParams
        lp.width = Math.max(1, t.rw.toInt())
        lp.height = Math.max(1, t.rh.toInt())
        h.layoutParams = lp
        h.translationX = t.rl - dl[0]
        h.translationY = t.rt - dl[1]
        h.visibility = View.VISIBLE
    }

    private fun detach(o: HomeItem) {
        layout.items.remove(o)
        layout.dock.remove(o)
    }

    private fun applyDrop(o: HomeItem, t: Target): Boolean {
        when (t.type) {
            T_CELL -> {
                detach(o)
                o.page = pager.currentItem
                o.row = t.row
                o.col = t.col
                layout.items.add(o)
                return true
            }
            T_MERGE, T_DOCK_MERGE -> {
                val tg = t.item ?: return false
                if (o.type != ItemType.APP) return false
                if (tg.type == ItemType.APP) {
                    detach(o)
                    layout.makeFolder(tg, o.component, "Folder")
                    return true
                }
                if (tg.type == ItemType.FOLDER) {
                    detach(o)
                    if (o.component !in tg.children) tg.children.add(o.component)
                    return true
                }
                return false
            }
            T_DOCK -> {
                detach(o)
                val idx = t.index.coerceIn(0, layout.dock.size)
                layout.dock.add(idx, o)
                return true
            }
        }
        return false
    }

    private fun endDrag(commit: Boolean) {
        ui.removeCallbacks(flipRunnable)
        edgeSide = 0
        val o = dragItem ?: return
        val src = dragSrc
        val cell = originCell
        val g = ghost
        val t = target
        dragLayer.dragActive = false
        hint?.let { dragLayer.removeView(it) }
        hint = null
        dragItem = null
        dragSrc = 0
        originCell = null
        ghost = null
        target = Target()

        var placed = false
        if (commit) placed = applyDrop(o, t)
        if (!placed && src == SRC_FOLDER) {
            placed = layout.addAtFree(o, pager.currentItem)
            if (!placed) act.toast("Layar utama penuh. Aplikasi tetap ada di Pustaka Aplikasi.")
            if (!placed) {
                if (g != null) dragLayer.removeView(g)
                save()
                refreshAll()
                renderDock()
                return
            }
        }

        if (placed) {
            if (g != null) dragLayer.removeView(g)
            finishChange(o)
            return
        }

        // Gagal dilepas: ikon kembali ke tempat asal
        if (src == SRC_HOME && o.page != pager.currentItem) {
            if (g != null) dragLayer.removeView(g)
            pager.setCurrentItem(o.page, false)
            refreshAll()
            return
        }
        if (g != null) {
            val dl = IntArray(2)
            dragLayer.getLocationOnScreen(dl)
            g.animate()
                .translationX((originLeft - dl[0]).toFloat())
                .translationY((originTop - dl[1]).toFloat())
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(180)
                .withEndAction {
                    dragLayer.removeView(g)
                    cell?.visibility = View.VISIBLE
                }
                .start()
        } else {
            cell?.visibility = View.VISIBLE
        }
    }

    private fun finishChange(o: HomeItem) {
        save()
        refreshAll()
        renderDock()
        forEachCell {
            if (it.item === o) {
                it.scaleX = 0.85f
                it.scaleY = 0.85f
                it.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }
        }
    }

    // ================================================================ tombol Home, Kembali, sentuhan

    fun handleBack(): Boolean {
        if (folderScrim != null) {
            closeFolder()
            return true
        }
        if (editing) {
            exitEdit()
            return true
        }
        return false
    }

    fun handleHome() {
        closeFolder()
        if (editing) exitEdit()
        pager.setCurrentItem(0, true)
    }

    fun isWidgetAt(x: Float, y: Float): Boolean {
        val pv = pageViews.get(pager.currentItem) ?: return false
        val r = Rect()
        for (j in 0 until pv.childCount) {
            val c = pv.getChildAt(j) as? CellView ?: continue
            if (c.item.type == ItemType.WIDGET && c.getGlobalVisibleRect(r) && r.contains(x.toInt(), y.toInt())) {
                return true
            }
        }
        return false
    }
}
