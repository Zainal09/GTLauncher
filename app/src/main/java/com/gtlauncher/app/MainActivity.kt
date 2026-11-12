package com.gtlauncher.app

import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private companion object {
        const val MAX_DOCK = 4
        const val HOST_ID = 1024
        const val REQ_BIND = 4001
        const val REQ_CONFIGURE = 4002
    }

    // ---- tampilan
    private lateinit var root: View
    private lateinit var homeLayer: View
    private lateinit var widget: LinearLayout
    private lateinit var clockView: TextClock
    private lateinit var dateView: TextClock
    private lateinit var hintView: TextView
    private lateinit var homePager: ViewPager2
    private lateinit var pageIndicator: PageIndicatorDotsView
    private lateinit var dock: LinearLayout
    private lateinit var drawerLayer: View
    private lateinit var searchInput: EditText
    private lateinit var recycler: RecyclerView

    // ---- data dan pengaturan
    private lateinit var cfg: LauncherPrefs
    private lateinit var drawerAdapter: AppAdapter
    private lateinit var homePagerAdapter: HomePageAdapter
    private lateinit var flingDetector: GestureDetector
    private lateinit var appWidgetManager: AppWidgetManager
    private lateinit var appWidgetHost: AppWidgetHost

    private val executor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    /** Ikon yang sudah diproses disimpan di sini, supaya tidak diproses ulang tiap kembali ke layar utama. */
    private val iconCache = ConcurrentHashMap<String, Drawable>()

    private var allAppsRaw: List<AppInfo> = emptyList()
    private var allApps: List<AppInfo> = emptyList()
    private var filtered: List<AppInfo> = emptyList()
    private var homeItems: MutableList<LauncherItem> = mutableListOf()

    private var drawerOpen = false
    private var receiverRegistered = false
    private var returnAnim = false
    private var pendingWidgetId = -1

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            val pkg = intent?.data?.schemeSpecificPart
            if (pkg != null) {
                iconCache.keys.toList().forEach { key ->
                    if (key.startsWith("$pkg/")) iconCache.remove(key)
                }

                if (action == Intent.ACTION_PACKAGE_REMOVED && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    // Hapus dari layar utama jika aplikasi dicopot
                    homeItems.removeAll { it.type == ItemType.APP && it.packageName == pkg }
                    cfg.setHomeItems(homeItems)
                } else if (action == Intent.ACTION_PACKAGE_ADDED && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    // Aplikasi baru terpasang: masukkan otomatis ke sel kosong
                    onNewPackageAdded(pkg)
                }
            }
            loadApps()
        }
    }

    // ================================================================ siklus hidup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContentView(R.layout.activity_main)
        cfg = LauncherPrefs(this)
        appWidgetManager = AppWidgetManager.getInstance(this)
        appWidgetHost = AppWidgetHost(this, HOST_ID)

        root = findViewById(R.id.root)
        homeLayer = findViewById(R.id.homeLayer)
        widget = findViewById(R.id.widget)
        clockView = findViewById(R.id.clock)
        dateView = findViewById(R.id.date)
        hintView = findViewById(R.id.hint)
        homePager = findViewById(R.id.homePager)
        pageIndicator = findViewById(R.id.pageIndicator)
        dock = findViewById(R.id.dock)
        drawerLayer = findViewById(R.id.drawerLayer)
        searchInput = findViewById(R.id.searchInput)
        recycler = findViewById(R.id.recycler)

        // Beri ruang untuk status bar, navigation bar, dan keyboard
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            homeLayer.setPadding(dp(16) + bars.left, bars.top, dp(16) + bars.right, bars.bottom)
            drawerLayer.setPadding(dp(20) + bars.left, bars.top, dp(20) + bars.right, bars.bottom)
            insets
        }

        // Daftar semua aplikasi (Pustaka Aplikasi)
        drawerAdapter = AppAdapter(
            onClick = { app, view -> launch(app, view) },
            onLongClick = { app, _ -> showAppMenu(app, fromHome = false) }
        )
        recycler.layoutManager = GridLayoutManager(this, cfg.columns)
        recycler.adapter = drawerAdapter
        recycler.setHasFixedSize(true)
        recycler.itemAnimator = null
        recycler.setItemViewCacheSize(24)

        // Multi-page adapter layar utama
        homePagerAdapter = HomePageAdapter(
            getColumns = { cfg.columns },
            getRows = { cfg.rows },
            getItemsForPage = { page -> homeItems.filter { it.page == page } },
            getAppInfo = { item -> allApps.find { it.packageName == item.packageName } },
            onItemClick = { _, app, view -> launch(app, view) },
            onItemLongClick = { item, app, _ -> showHomeItemMenu(item, app) },
            onPageLongClick = { _ -> showHomeMenu() },
            onWidgetCreateView = { wid -> createWidgetView(wid) },
            getIconPx = { dp(cfg.iconSizeDp) },
            getShowLabels = { cfg.showLabels },
            getLabelColor = { LauncherPrefs.LABEL_COLORS[cfg.labelColorIndex] }
        )
        homePager.adapter = homePagerAdapter
        pageIndicator.attachToViewPager(homePager)

        // Ketuk widget jam -> buka aplikasi jam. Tahan area kosong -> menu layar utama.
        widget.setOnClickListener { openClockApp() }
        widget.setOnLongClickListener {
            showHomeMenu()
            true
        }
        homeLayer.setOnLongClickListener {
            showHomeMenu()
            true
        }

        setupGestures()
        setupSearch()
        applyStyles()
    }

    override fun onStart() {
        super.onStart()
        applyRefreshRate()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(packageReceiver, filter)
        }
        receiverRegistered = true

        try {
            appWidgetHost.startListening()
        } catch (e: Exception) {
            // beberapa perangkat menolak, widget tetap tampil tapi tidak ter-update otomatis
        }
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        // Efek halus saat kembali dari aplikasi ke layar utama
        if (returnAnim) {
            returnAnim = false
            if (!drawerOpen) {
                homeLayer.animate().cancel()
                homeLayer.alpha = 0.5f
                homeLayer.scaleX = 1.05f
                homeLayer.scaleY = 1.05f
                homeLayer.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(240)
                    .withLayer()
                    .start()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            appWidgetHost.stopListening()
        } catch (e: Exception) {
            // abaikan
        }
        if (receiverRegistered) {
            try {
                unregisterReceiver(packageReceiver)
            } catch (e: IllegalArgumentException) {
                // sudah tidak terdaftar, abaikan
            }
            receiverRegistered = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Tombol Home ditekan saat launcher sudah terbuka -> kembali ke halaman 0 dan tutup drawer
        if (intent != null &&
            intent.action == Intent.ACTION_MAIN &&
            intent.hasCategory(Intent.CATEGORY_HOME)
        ) {
            closeDrawer()
            if (::homePager.isInitialized && homePager.currentItem != 0) {
                homePager.setCurrentItem(0, true)
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Launcher tidak boleh menutup dirinya sendiri; cukup tutup Pustaka Aplikasi
        if (drawerOpen) closeDrawer()
    }

    // ================================================================ gesture

    private fun setupGestures() {
        flingDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (Math.abs(dy) < Math.abs(dx)) return false // geseran mendatar (diurus ViewPager2)

                if (drawerOpen) {
                    // Geser cepat ke bawah saat daftar sudah di paling atas -> tutup
                    if (velocityY > 2000f && dy > dp(80) && !recycler.canScrollVertically(-1)) {
                        closeDrawer()
                        return true
                    }
                    return false
                }

                if (velocityY < -1200f && dy < -dp(80)) {
                    // Geser ke atas: buka Pustaka Aplikasi
                    openDrawer()
                    return true
                } else if (velocityY > 1500f && dy > dp(80)) {
                    // Geser ke bawah di layar utama: panel notifikasi
                    expandNotifications()
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::flingDetector.isInitialized) flingDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun expandNotifications() {
        try {
            val sbs = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager")
                .getMethod("expandNotificationsPanel")
                .invoke(sbs)
        } catch (e: Exception) {
            // tidak didukung di perangkat ini, abaikan
        }
    }

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                applyFilter()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        // Tombol "Go" di keyboard membuka hasil teratas
        searchInput.setOnEditorActionListener { _, _, _ ->
            filtered.firstOrNull()?.let { launch(it, null) }
            true
        }
    }

    // ================================================================ data aplikasi

    private fun loadApps() {
        executor.execute {
            val pm = packageManager
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val raw = pm.queryIntentActivities(query, 0)
                .filter { it.activityInfo.packageName != packageName }
                .mapNotNull { resolveInfo ->
                    try {
                        val comp = ComponentName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name)
                        val key = comp.flattenToString()
                        val icon = iconCache[key] ?: try {
                            resolveInfo.loadIcon(pm).toIosBitmapDrawable(this@MainActivity)
                        } catch (e: Exception) {
                            pm.defaultActivityIcon.toIosBitmapDrawable(this@MainActivity)
                        }.also { d -> iconCache[key] = d }

                        AppInfo(
                            label = try {
                                resolveInfo.loadLabel(pm).toString()
                            } catch (e: Exception) {
                                resolveInfo.activityInfo.packageName
                            },
                            packageName = resolveInfo.activityInfo.packageName,
                            component = comp,
                            icon = icon
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                .sortedBy { it.label.lowercase() }

            ui.post {
                if (isFinishing || isDestroyed) return@post

                val same = raw.size == allAppsRaw.size && raw.indices.all {
                    raw[it].component == allAppsRaw[it].component && raw[it].label == allAppsRaw[it].label
                }
                if (!same) {
                    allAppsRaw = raw
                    rebuildVisible()
                }
            }
        }
    }

    /** Hitung ulang daftar yang terlihat lalu perbarui semua tampilan. */
    private fun rebuildVisible() {
        val hidden = cfg.hidden
        allApps = allAppsRaw.filter { it.packageName !in hidden }
        applyFilter()
        renderHome()
        renderDock()
    }

    private fun applyFilter() {
        val q = searchInput.text.toString().trim()
        filtered = if (q.isEmpty()) {
            allApps
        } else {
            allApps.filter { it.label.contains(q, ignoreCase = true) }
        }
        drawerAdapter.submit(filtered)
    }

    // ================================================================ tampilan dan pengaturan

    private fun applyStyles() {
        val iconPx = dp(cfg.iconSizeDp)
        val labelColor = LauncherPrefs.LABEL_COLORS[cfg.labelColorIndex]

        drawerAdapter.applyStyle(iconPx, cfg.showLabels, Color.WHITE)
        (recycler.layoutManager as GridLayoutManager).spanCount = cfg.columns

        // Teks petunjuk mengikuti warna nama aplikasi
        hintView.setTextColor((labelColor and 0x00FFFFFF) or 0xB3000000.toInt())

        // Widget jam
        widget.visibility = if (cfg.clockVisible) View.VISIBLE else View.GONE
        val fmt = if (cfg.clock24) "HH:mm" else "h:mm"
        clockView.format24Hour = fmt
        clockView.format12Hour = fmt
        val clockColor = LauncherPrefs.CLOCK_COLORS[cfg.clockColorIndex]
        clockView.setTextColor(clockColor)
        dateView.setTextColor((clockColor and 0x00FFFFFF) or 0xCC000000.toInt())
        dateView.visibility = if (cfg.showDate) View.VISIBLE else View.GONE

        // Transparansi kaca untuk widget jam dan dock
        val fill = Color.argb(LauncherPrefs.GLASS_ALPHAS[cfg.glassIndex], 255, 255, 255)
        (widget.background.mutate() as? GradientDrawable)?.setColor(fill)
        (dock.background.mutate() as? GradientDrawable)?.setColor(fill)

        renderDock()
        renderHome()
        applyRefreshRate()
    }

    @Suppress("DEPRECATION")
    private fun applyRefreshRate() {
        try {
            val lp = window.attributes
            if (cfg.highRefresh) {
                val display = windowManager.defaultDisplay
                val current = display.mode
                val best = display.supportedModes
                    .filter {
                        it.physicalWidth == current.physicalWidth &&
                            it.physicalHeight == current.physicalHeight
                    }
                    .maxByOrNull { it.refreshRate }
                if (best != null) lp.preferredDisplayModeId = best.modeId
            } else {
                lp.preferredDisplayModeId = 0
            }
            window.attributes = lp
        } catch (e: Exception) {
            // tidak didukung, abaikan
        }
    }

    // ================================================================ buka aplikasi

    private fun launch(app: AppInfo, source: View?) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            if (source != null && source.width > 0 && source.height > 0) {
                val opts = ActivityOptions.makeScaleUpAnimation(
                    source, 0, 0, source.width, source.height
                )
                startActivity(intent, opts.toBundle())
            } else {
                startActivity(intent)
            }
            returnAnim = true
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak bisa membuka ${app.label}", Toast.LENGTH_SHORT).show()
        }
        if (drawerOpen) closeDrawer(immediate = true)
    }

    private fun openClockApp() {
        try {
            startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            returnAnim = true
        } catch (e: Exception) {
            // tidak ada aplikasi jam, abaikan
        }
    }

    // ================================================================ Pustaka Aplikasi (drawer)

    private fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true
        drawerLayer.visibility = View.VISIBLE
        drawerLayer.alpha = 0f
        drawerLayer.translationY = dp(60).toFloat()
        drawerLayer.animate().alpha(1f).translationY(0f).setDuration(220).withLayer().start()
        homeLayer.animate().alpha(0f).setDuration(180).withLayer().start()
    }

    private fun closeDrawer(immediate: Boolean = false) {
        if (!drawerOpen) return
        drawerOpen = false
        hideKeyboard()
        searchInput.setText("")
        searchInput.clearFocus()
        recycler.scrollToPosition(0)

        if (immediate) {
            drawerLayer.animate().cancel()
            drawerLayer.visibility = View.GONE
            homeLayer.animate().cancel()
            homeLayer.alpha = 1f
        } else {
            drawerLayer.animate()
                .alpha(0f)
                .translationY(dp(60).toFloat())
                .setDuration(180)
                .withLayer()
                .withEndAction {
                    if (!drawerOpen) drawerLayer.visibility = View.GONE
                }
                .start()
            homeLayer.animate().alpha(1f).setDuration(200).withLayer().start()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    // ================================================================ dock

    private fun resolvePackages(intents: List<Intent>): List<String> =
        intents.mapNotNull {
            packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.distinct()

    private fun defaultDock(): List<String> = resolvePackages(
        listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        )
    )

    private fun dockPackages(): List<String> {
        val pkgs = cfg.getList("dock") ?: defaultDock()
        return pkgs.filter { p -> allApps.any { it.packageName == p } }.take(MAX_DOCK)
    }

    private fun renderDock() {
        dock.removeAllViews()
        val size = dp(cfg.iconSizeDp)
        dockPackages()
            .mapNotNull { pkg -> allApps.find { it.packageName == pkg } }
            .forEach { app ->
                val cell = FrameLayout(this)
                cell.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

                val iv = ImageView(this)
                iv.setImageDrawable(app.icon.constantState?.newDrawable(resources) ?: app.icon)
                iv.layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
                iv.pressEffect()
                iv.setOnClickListener { launch(app, iv) }
                iv.setOnLongClickListener {
                    removeFromDock(app)
                    true
                }
                cell.addView(iv)
                dock.addView(cell)
            }
    }

    private fun addToDock(app: AppInfo) {
        val current = dockPackages().toMutableList()
        if (app.packageName in current) {
            Toast.makeText(this, "Sudah ada di dock", Toast.LENGTH_SHORT).show()
            return
        }
        if (current.size >= MAX_DOCK) {
            Toast.makeText(
                this,
                "Dock penuh (maks. $MAX_DOCK). Tahan ikon di dock untuk menghapus.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        current.add(app.packageName)
        cfg.setList("dock", current)
        renderDock()
        Toast.makeText(this, "${app.label} ditambahkan ke dock", Toast.LENGTH_SHORT).show()
    }

    private fun removeFromDock(app: AppInfo) {
        val current = dockPackages().toMutableList()
        current.remove(app.packageName)
        cfg.setList("dock", current)
        renderDock()
        Toast.makeText(this, "${app.label} dihapus dari dock", Toast.LENGTH_SHORT).show()
    }

    // ================================================================ halaman layar utama

    private fun defaultHome(): List<String> {
        val cats = listOf(
            Intent.CATEGORY_APP_GALLERY,
            Intent.CATEGORY_APP_CALENDAR,
            Intent.CATEGORY_APP_CALCULATOR,
            Intent.CATEGORY_APP_MAPS,
            Intent.CATEGORY_APP_MUSIC,
            Intent.CATEGORY_APP_MARKET
        )
        val intents = cats.map { Intent(Intent.ACTION_MAIN).addCategory(it) } +
            Intent(Settings.ACTION_SETTINGS)
        return resolvePackages(intents)
    }

    private fun renderHome() {
        if (homeItems.isEmpty()) {
            homeItems = cfg.migrateOldHomeIfNeeded { defaultHome() }.toMutableList()
        }

        val maxItemPage = homeItems.maxOfOrNull { it.page } ?: 0
        val totalPages = maxOf(cfg.pageCount, maxItemPage + 1, 1)

        homePagerAdapter.pageCount = totalPages
        pageIndicator.pageCount = totalPages
        homePagerAdapter.notifyDataSetChanged()
    }

    /**
     * Menempatkan aplikasi baru yang baru terpasang di sel kosong pertama.
     */
    private fun onNewPackageAdded(pkg: String) {
        if (pkg in cfg.hidden) return
        if (homeItems.any { it.type == ItemType.APP && it.packageName == pkg }) return

        val cols = cfg.columns
        val rows = cfg.rows
        var targetPage = 0
        var targetPos: Pair<Int, Int>? = null

        val totalPages = maxOf(cfg.pageCount, (homeItems.maxOfOrNull { it.page } ?: 0) + 1, 1)
        for (p in 0 until totalPages) {
            val occupied = Array(cols) { BooleanArray(rows) }
            homeItems.filter { it.page == p }.forEach { item ->
                for (x in item.cellX until minOf(cols, item.cellX + item.spanX)) {
                    for (y in item.cellY until minOf(rows, item.cellY + item.spanY)) {
                        occupied[x][y] = true
                    }
                }
            }
            for (y in 0 until rows) {
                for (x in 0 until cols) {
                    if (!occupied[x][y]) {
                        targetPage = p
                        targetPos = Pair(x, y)
                        break
                    }
                }
                if (targetPos != null) break
            }
            if (targetPos != null) break
        }

        if (targetPos == null) {
            targetPage = totalPages
            targetPos = Pair(0, 0)
            cfg.pageCount = targetPage + 1
        }

        homeItems.add(
            LauncherItem(
                type = ItemType.APP,
                page = targetPage,
                cellX = targetPos.first,
                cellY = targetPos.second,
                spanX = 1,
                spanY = 1,
                packageName = pkg
            )
        )
        cfg.setHomeItems(homeItems)
    }

    private fun addToHome(app: AppInfo) {
        if (cfg.lockLayout) {
            Toast.makeText(this, "Tata letak terkunci", Toast.LENGTH_SHORT).show()
            return
        }

        val cols = cfg.columns
        val rows = cfg.rows
        var targetPage = homePager.currentItem
        var targetPos: Pair<Int, Int>? = null

        fun findVacantOnPage(p: Int): Pair<Int, Int>? {
            val occupied = Array(cols) { BooleanArray(rows) }
            homeItems.filter { it.page == p }.forEach { item ->
                for (x in item.cellX until minOf(cols, item.cellX + item.spanX)) {
                    for (y in item.cellY until minOf(rows, item.cellY + item.spanY)) {
                        occupied[x][y] = true
                    }
                }
            }
            for (y in 0 until rows) {
                for (x in 0 until cols) {
                    if (!occupied[x][y]) return Pair(x, y)
                }
            }
            return null
        }

        targetPos = findVacantOnPage(targetPage)
        if (targetPos == null) {
            val totalPages = maxOf(cfg.pageCount, (homeItems.maxOfOrNull { it.page } ?: 0) + 1, 1)
            for (p in 0 until totalPages) {
                if (p == targetPage) continue
                val pos = findVacantOnPage(p)
                if (pos != null) {
                    targetPage = p
                    targetPos = pos
                    break
                }
            }
        }

        if (targetPos == null) {
            targetPage = maxOf(cfg.pageCount, (homeItems.maxOfOrNull { it.page } ?: 0) + 1, 1)
            targetPos = Pair(0, 0)
            cfg.pageCount = targetPage + 1
        }

        homeItems.add(
            LauncherItem(
                type = ItemType.APP,
                page = targetPage,
                cellX = targetPos.first,
                cellY = targetPos.second,
                spanX = 1,
                spanY = 1,
                packageName = app.packageName,
                componentName = app.component.flattenToString()
            )
        )
        cfg.setHomeItems(homeItems)
        renderHome()
        Toast.makeText(this, "${app.label} ditambahkan ke halaman ${targetPage + 1}", Toast.LENGTH_SHORT).show()
    }

    private fun removeFromHome(item: LauncherItem) {
        if (cfg.lockLayout) {
            Toast.makeText(this, "Tata letak terkunci", Toast.LENGTH_SHORT).show()
            return
        }
        homeItems.removeAll { it.id == item.id }
        cfg.setHomeItems(homeItems)
        renderHome()
        Toast.makeText(this, "Dihapus dari layar utama", Toast.LENGTH_SHORT).show()
    }

    // ================================================================ widget Android

    private fun createWidgetView(widgetId: Int): View? {
        val info = appWidgetManager.getAppWidgetInfo(widgetId) ?: return null
        return try {
            val hv = appWidgetHost.createView(this, widgetId, info)
            hv.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(22).toFloat())
                }
            }
            hv.clipToOutline = true
            hv
        } catch (e: Exception) {
            null
        }
    }

    private fun showWidgetPicker() {
        val pm = packageManager
        val raw = try {
            appWidgetManager.installedProviders
        } catch (e: Exception) {
            emptyList<AppWidgetProviderInfo>()
        }
        val providers = raw.sortedBy { it.loadLabel(pm).orEmpty().lowercase() }
        if (providers.isEmpty()) {
            Toast.makeText(this, "Tidak ada widget tersedia", Toast.LENGTH_SHORT).show()
            return
        }
        val names = providers.map { info ->
            val app = try {
                pm.getApplicationInfo(info.provider.packageName, 0).loadLabel(pm).toString()
            } catch (e: Exception) {
                info.provider.packageName
            }
            "$app  •  ${info.loadLabel(pm)}"
        }
        AlertDialog.Builder(this)
            .setTitle("Tambah widget")
            .setItems(names.toTypedArray()) { _, which -> addWidget(providers[which]) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun addWidget(info: AppWidgetProviderInfo) {
        val id = appWidgetHost.allocateAppWidgetId()
        pendingWidgetId = id
        try {
            if (appWidgetManager.bindAppWidgetIdIfAllowed(id, info.provider)) {
                configureOrFinish(id, info)
            } else {
                val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                startActivityForResult(intent, REQ_BIND)
            }
        } catch (e: Exception) {
            cancelPendingWidget()
            Toast.makeText(this, "Widget ini tidak bisa dipasang", Toast.LENGTH_SHORT).show()
        }
    }

    private fun configureOrFinish(id: Int, info: AppWidgetProviderInfo) {
        if (info.configure != null) {
            try {
                appWidgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE, null)
            } catch (e: Exception) {
                finishAddWidget(id)
            }
        } else {
            finishAddWidget(id)
        }
    }

    private fun finishAddWidget(id: Int) {
        val cols = cfg.columns
        val rows = cfg.rows
        val spanX = minOf(cols, 4)
        val spanY = 2

        var targetPage = homePager.currentItem
        var targetPos: Pair<Int, Int>? = null

        fun findVacantWidgetSpan(p: Int): Pair<Int, Int>? {
            val occupied = Array(cols) { BooleanArray(rows) }
            homeItems.filter { it.page == p }.forEach { item ->
                for (x in item.cellX until minOf(cols, item.cellX + item.spanX)) {
                    for (y in item.cellY until minOf(rows, item.cellY + item.spanY)) {
                        occupied[x][y] = true
                    }
                }
            }
            for (y in 0..(rows - spanY)) {
                for (x in 0..(cols - spanX)) {
                    var canFit = true
                    for (dx in 0 until spanX) {
                        for (dy in 0 until spanY) {
                            if (occupied[x + dx][y + dy]) {
                                canFit = false
                                break
                            }
                        }
                        if (!canFit) break
                    }
                    if (canFit) return Pair(x, y)
                }
            }
            return null
        }

        targetPos = findVacantWidgetSpan(targetPage)
        if (targetPos == null) {
            val totalPages = maxOf(cfg.pageCount, (homeItems.maxOfOrNull { it.page } ?: 0) + 1, 1)
            for (p in 0 until totalPages) {
                if (p == targetPage) continue
                val pos = findVacantWidgetSpan(p)
                if (pos != null) {
                    targetPage = p
                    targetPos = pos
                    break
                }
            }
        }

        if (targetPos == null) {
            targetPage = maxOf(cfg.pageCount, (homeItems.maxOfOrNull { it.page } ?: 0) + 1, 1)
            targetPos = Pair(0, 0)
            cfg.pageCount = targetPage + 1
        }

        homeItems.add(
            LauncherItem(
                type = ItemType.WIDGET,
                page = targetPage,
                cellX = targetPos.first,
                cellY = targetPos.second,
                spanX = spanX,
                spanY = spanY,
                widgetId = id
            )
        )
        cfg.setHomeItems(homeItems)
        cfg.widgetIds = cfg.widgetIds + id
        pendingWidgetId = -1
        renderHome()
        Toast.makeText(this, "Widget ditambahkan ke halaman ${targetPage + 1}", Toast.LENGTH_SHORT).show()
    }

    private fun cancelPendingWidget() {
        if (pendingWidgetId != -1) {
            try {
                appWidgetHost.deleteAppWidgetId(pendingWidgetId)
            } catch (e: Exception) {
                // abaikan
            }
            pendingWidgetId = -1
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val id = pendingWidgetId
        when (requestCode) {
            REQ_BIND -> {
                if (resultCode == RESULT_OK && id != -1) {
                    val info = appWidgetManager.getAppWidgetInfo(id)
                    if (info != null) configureOrFinish(id, info) else cancelPendingWidget()
                } else {
                    cancelPendingWidget()
                }
            }
            REQ_CONFIGURE -> {
                if (resultCode == RESULT_OK && id != -1) finishAddWidget(id) else cancelPendingWidget()
            }
        }
    }

    private fun showRemoveWidget() {
        val widgetItems = homeItems.filter { it.type == ItemType.WIDGET && it.widgetId != null }
        if (widgetItems.isEmpty()) {
            Toast.makeText(this, "Belum ada widget di layar utama", Toast.LENGTH_SHORT).show()
            return
        }
        val pm = packageManager
        val names = widgetItems.map { item ->
            val id = item.widgetId ?: 0
            val info = appWidgetManager.getAppWidgetInfo(id)
            val label = info?.loadLabel(pm)?.toString() ?: "Widget"
            "Hal. ${item.page + 1}: $label"
        }
        AlertDialog.Builder(this)
            .setTitle("Hapus widget")
            .setItems(names.toTypedArray()) { _, which ->
                val item = widgetItems[which]
                val wid = item.widgetId
                if (wid != null) {
                    try {
                        appWidgetHost.deleteAppWidgetId(wid)
                    } catch (e: Exception) {
                        // abaikan
                    }
                    cfg.widgetIds = cfg.widgetIds - wid
                }
                homeItems.remove(item)
                cfg.setHomeItems(homeItems)
                renderHome()
                Toast.makeText(this, "Widget dihapus", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ================================================================ menu

    private fun showHomeMenu() {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (!cfg.lockLayout) {
            labels.add("Tambah widget")
            actions.add { showWidgetPicker() }
            if (homeItems.any { it.type == ItemType.WIDGET }) {
                labels.add("Hapus widget")
                actions.add { showRemoveWidget() }
            }
        }
        labels.add("Ganti wallpaper")
        actions.add { openWallpaperPicker() }
        labels.add("Pengaturan launcher")
        actions.add { showSettings() }

        AlertDialog.Builder(this)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun showHomeItemMenu(item: LauncherItem, app: AppInfo) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (!cfg.lockLayout) {
            labels.add("Hapus dari layar utama")
            actions.add { removeFromHome(item) }
        }
        labels.add("Info aplikasi")
        actions.add {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", app.packageName, null)
                )
            )
        }
        if (!cfg.lockLayout) {
            labels.add("Copot pemasangan")
            actions.add {
                startActivity(
                    Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))
                )
            }
        }

        AlertDialog.Builder(this)
            .setTitle(item.customTitle ?: app.label)
            .setItems(labels.toTypedArray()) { _, which ->
                try {
                    actions[which]()
                } catch (e: Exception) {
                    Toast.makeText(this, "Aksi tidak tersedia", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showAppMenu(app: AppInfo, fromHome: Boolean) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        labels.add("Tambah ke layar utama")
        actions.add { addToHome(app) }
        labels.add("Tambah ke dock")
        actions.add { addToDock(app) }
        labels.add("Sembunyikan aplikasi")
        actions.add {
            cfg.hidden = cfg.hidden + app.packageName
            rebuildVisible()
            Toast.makeText(
                this,
                "${app.label} disembunyikan (bisa ditampilkan lagi di Pengaturan)",
                Toast.LENGTH_LONG
            ).show()
        }
        labels.add("Info aplikasi")
        actions.add {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", app.packageName, null)
                )
            )
        }
        labels.add("Copot pemasangan")
        actions.add {
            startActivity(
                Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))
            )
        }

        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(labels.toTypedArray()) { _, which ->
                try {
                    actions[which]()
                } catch (e: Exception) {
                    Toast.makeText(this, "Aksi tidak tersedia", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun openWallpaperPicker() {
        try {
            startActivity(
                Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Ganti wallpaper")
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak bisa membuka pengaturan wallpaper", Toast.LENGTH_SHORT).show()
        }
    }

    // ================================================================ pengaturan

    private fun chooseOne(title: String, options: Array<String>, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(options, current) { dialog, which ->
                onPick(which)
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun onOff(value: Boolean, on: String, off: String): String = if (value) on else off

    private fun showSettings() {
        val items = arrayOf(
            "Ukuran ikon: ${LauncherPrefs.ICON_SIZE_NAMES[cfg.iconSizeIndex]}",
            "Ukuran grid: ${LauncherPrefs.GRID_NAMES[cfg.gridIndex]}",
            "Kunci tata letak: ${onOff(cfg.lockLayout, "Terkunci", "Terbuka")}",
            "Nama aplikasi: ${onOff(cfg.showLabels, "Tampil", "Sembunyi")}",
            "Warna nama di layar utama: ${LauncherPrefs.LABEL_COLOR_NAMES[cfg.labelColorIndex]}",
            "Pengaturan widget jam",
            "Transparansi kaca: ${LauncherPrefs.GLASS_NAMES[cfg.glassIndex]}",
            "Refresh rate tertinggi: ${onOff(cfg.highRefresh, "Aktif", "Mati")}",
            "Kelola halaman (${homePagerAdapter.pageCount} halaman)",
            "Aplikasi tersembunyi (${cfg.hidden.size})",
            "Atur ulang layar utama dan dock",
            "Jadikan launcher default"
        )
        AlertDialog.Builder(this)
            .setTitle("Pengaturan GT Launcher")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> chooseOne("Ukuran ikon", LauncherPrefs.ICON_SIZE_NAMES, cfg.iconSizeIndex) {
                        cfg.iconSizeIndex = it
                        applyStyles()
                        showSettings()
                    }
                    1 -> chooseOne("Ukuran grid", LauncherPrefs.GRID_NAMES, cfg.gridIndex) {
                        cfg.gridIndex = it
                        applyStyles()
                        showSettings()
                    }
                    2 -> {
                        cfg.lockLayout = !cfg.lockLayout
                        val msg = if (cfg.lockLayout) "Tata letak dikunci" else "Tata letak dibuka"
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        showSettings()
                    }
                    3 -> chooseOne("Nama aplikasi", arrayOf("Tampil", "Sembunyi"), if (cfg.showLabels) 0 else 1) {
                        cfg.showLabels = (it == 0)
                        applyStyles()
                        showSettings()
                    }
                    4 -> chooseOne("Warna nama di layar utama", LauncherPrefs.LABEL_COLOR_NAMES, cfg.labelColorIndex) {
                        cfg.labelColorIndex = it
                        applyStyles()
                        showSettings()
                    }
                    5 -> showClockSettings()
                    6 -> chooseOne("Transparansi kaca", LauncherPrefs.GLASS_NAMES, cfg.glassIndex) {
                        cfg.glassIndex = it
                        applyStyles()
                        showSettings()
                    }
                    7 -> chooseOne("Refresh rate tertinggi", arrayOf("Aktif", "Mati"), if (cfg.highRefresh) 0 else 1) {
                        cfg.highRefresh = (it == 0)
                        applyStyles()
                        showSettings()
                    }
                    8 -> showPageManager()
                    9 -> showHiddenApps()
                    10 -> confirmReset()
                    11 -> openDefaultLauncherSettings()
                }
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun showPageManager() {
        val total = homePagerAdapter.pageCount
        val options = arrayOf(
            "Tambah halaman kosong di akhir",
            "Hapus halaman kosong terakhir"
        )
        AlertDialog.Builder(this)
            .setTitle("Kelola halaman ($total halaman)")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        cfg.pageCount = total + 1
                        renderHome()
                        Toast.makeText(this, "Halaman ${total + 1} ditambahkan", Toast.LENGTH_SHORT).show()
                        showSettings()
                    }
                    1 -> {
                        if (total <= 1) {
                            Toast.makeText(this, "Minimal harus ada 1 halaman", Toast.LENGTH_SHORT).show()
                            showSettings()
                            return@setItems
                        }
                        val lastPageIndex = total - 1
                        val hasItems = homeItems.any { it.page == lastPageIndex }
                        if (hasItems) {
                            Toast.makeText(this, "Halaman terakhir masih berisi aplikasi/widget", Toast.LENGTH_SHORT).show()
                        } else {
                            cfg.pageCount = total - 1
                            renderHome()
                            Toast.makeText(this, "Halaman $total dihapus", Toast.LENGTH_SHORT).show()
                        }
                        showSettings()
                    }
                }
            }
            .setNegativeButton("Kembali") { _, _ -> showSettings() }
            .show()
    }

    private fun showClockSettings() {
        val items = arrayOf(
            "Widget jam: ${onOff(cfg.clockVisible, "Tampil", "Sembunyi")}",
            "Format jam: ${onOff(cfg.clock24, "24 jam", "12 jam")}",
            "Tanggal: ${onOff(cfg.showDate, "Tampil", "Sembunyi")}",
            "Warna teks: ${LauncherPrefs.CLOCK_COLOR_NAMES[cfg.clockColorIndex]}"
        )
        AlertDialog.Builder(this)
            .setTitle("Pengaturan widget jam")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> chooseOne("Widget jam", arrayOf("Tampil", "Sembunyi"), if (cfg.clockVisible) 0 else 1) {
                        cfg.clockVisible = (it == 0)
                        applyStyles()
                        showClockSettings()
                    }
                    1 -> chooseOne("Format jam", arrayOf("24 jam", "12 jam"), if (cfg.clock24) 0 else 1) {
                        cfg.clock24 = (it == 0)
                        applyStyles()
                        showClockSettings()
                    }
                    2 -> chooseOne("Tanggal", arrayOf("Tampil", "Sembunyi"), if (cfg.showDate) 0 else 1) {
                        cfg.showDate = (it == 0)
                        applyStyles()
                        showClockSettings()
                    }
                    3 -> chooseOne("Warna teks", LauncherPrefs.CLOCK_COLOR_NAMES, cfg.clockColorIndex) {
                        cfg.clockColorIndex = it
                        applyStyles()
                        showClockSettings()
                    }
                }
            }
            .setNegativeButton("Kembali") { _, _ -> showSettings() }
            .show()
    }

    private fun showHiddenApps() {
        val hidden = cfg.hidden
        val apps = allAppsRaw.filter { it.packageName in hidden }
        if (apps.isEmpty()) {
            Toast.makeText(this, "Tidak ada aplikasi tersembunyi", Toast.LENGTH_SHORT).show()
            showSettings()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Ketuk untuk menampilkan kembali")
            .setItems(apps.map { it.label }.toTypedArray()) { _, which ->
                cfg.hidden = cfg.hidden - apps[which].packageName
                rebuildVisible()
                showHiddenApps()
            }
            .setNegativeButton("Kembali") { _, _ -> showSettings() }
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Atur ulang?")
            .setMessage("Isi layar utama dan dock dikembalikan ke bawaan. Pengaturan lain dan widget tidak berubah.")
            .setPositiveButton("Atur ulang") { _, _ ->
                cfg.resetLayout()
                homeItems.clear()
                renderHome()
                renderDock()
                Toast.makeText(this, "Layar utama dan dock diatur ulang", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun openDefaultLauncherSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "Tidak bisa membuka pengaturan", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ================================================================ util

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
