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
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Grid yang tidak bisa di-scroll sendiri (scroll-nya diurus ScrollView layar utama). */
private class FixedGridLayoutManager(context: Context, span: Int) : GridLayoutManager(context, span) {
    override fun canScrollVertically(): Boolean = false
}

class MainActivity : Activity() {

    private companion object {
        const val MAX_DOCK = 4
        const val MAX_HOME = 12
        const val HOST_ID = 1024
        const val REQ_BIND = 4001
        const val REQ_CONFIGURE = 4002
    }

    // ---- tampilan
    private lateinit var root: View
    private lateinit var homeLayer: View
    private lateinit var homeScroll: ScrollView
    private lateinit var homeContent: View
    private lateinit var widget: LinearLayout
    private lateinit var clockView: TextClock
    private lateinit var dateView: TextClock
    private lateinit var hintView: TextView
    private lateinit var widgetContainer: LinearLayout
    private lateinit var homeRecycler: RecyclerView
    private lateinit var dock: LinearLayout
    private lateinit var drawerLayer: View
    private lateinit var searchInput: EditText
    private lateinit var recycler: RecyclerView

    // ---- data dan pengaturan
    private lateinit var cfg: LauncherPrefs
    private lateinit var drawerAdapter: AppAdapter
    private lateinit var homeAdapter: AppAdapter
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
    private var drawerOpen = false
    private var receiverRegistered = false
    private var returnAnim = false
    private var pendingWidgetId = -1

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Aplikasi yang diperbarui atau dihapus: buang ikon lamanya dari cache
            val pkg = intent?.data?.schemeSpecificPart
            if (pkg != null) {
                iconCache.keys.toList().forEach { key ->
                    if (key.startsWith("$pkg/")) iconCache.remove(key)
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
        homeScroll = findViewById(R.id.homeScroll)
        homeContent = findViewById(R.id.homeContent)
        widget = findViewById(R.id.widget)
        clockView = findViewById(R.id.clock)
        dateView = findViewById(R.id.date)
        hintView = findViewById(R.id.hint)
        widgetContainer = findViewById(R.id.widgetContainer)
        homeRecycler = findViewById(R.id.homeRecycler)
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

        // Ikon di layar utama
        homeAdapter = AppAdapter(
            onClick = { app, view -> launch(app, view) },
            onLongClick = { app, _ -> showAppMenu(app, fromHome = true) }
        )
        homeRecycler.layoutManager = FixedGridLayoutManager(this, cfg.columns)
        homeRecycler.adapter = homeAdapter
        homeRecycler.itemAnimator = null
        homeRecycler.isNestedScrollingEnabled = false

        // Ketuk widget jam -> buka aplikasi jam. Tahan area kosong -> menu layar utama.
        widget.setOnClickListener { openClockApp() }
        widget.setOnLongClickListener {
            showHomeMenu()
            true
        }
        homeContent.setOnLongClickListener {
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
        renderWidgets()
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
        // Tombol Home ditekan saat launcher sudah terbuka -> kembali ke layar utama paling atas
        if (intent != null &&
            intent.action == Intent.ACTION_MAIN &&
            intent.hasCategory(Intent.CATEGORY_HOME)
        ) {
            closeDrawer()
            homeScroll.smoothScrollTo(0, 0)
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
                if (Math.abs(dy) < Math.abs(dx)) return false // geseran mendatar, abaikan

                if (drawerOpen) {
                    // Geser cepat ke bawah saat daftar sudah di paling atas -> tutup
                    if (velocityY > 2000f && dy > dp(80) && !recycler.canScrollVertically(-1)) {
                        closeDrawer()
                        return true
                    }
                    return false
                }

                if (velocityY < -1200f && dy < -dp(80)) {
                    // Geser ke atas: buka Pustaka Aplikasi (dari dock, atau kalau layar utama sudah di dasar)
                    if (isInside(dock, e1.x, e1.y) || !homeScroll.canScrollVertically(1)) {
                        openDrawer()
                        return true
                    }
                } else if (velocityY > 1500f && dy > dp(80)) {
                    // Geser ke bawah di layar utama paling atas: panel notifikasi
                    if (!homeScroll.canScrollVertically(-1)) {
                        expandNotifications()
                        return true
                    }
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::flingDetector.isInitialized) flingDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun isInside(v: View, x: Float, y: Float): Boolean {
        val r = Rect()
        if (!v.getGlobalVisibleRect(r)) return false
        return r.contains(x.toInt(), y.toInt())
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
                .map {
                    val comp = ComponentName(it.activityInfo.packageName, it.activityInfo.name)
                    val key = comp.flattenToString()
                    // Pakai ikon dari cache kalau ada; hanya aplikasi baru yang diproses
                    val icon = iconCache[key] ?: it.loadIcon(pm).toIosBitmapDrawable(this).also { d ->
                        iconCache[key] = d
                    }
                    AppInfo(
                        label = it.loadLabel(pm).toString(),
                        packageName = it.activityInfo.packageName,
                        component = comp,
                        icon = icon
                    )
                }
                .sortedBy { it.label.lowercase() }

            ui.post {
                // Tidak ada perubahan -> jangan gambar ulang (mencegah kedip saat kembali dari aplikasi)
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

    /** Hitung ulang daftar yang terlihat (tanpa aplikasi yang disembunyikan) lalu perbarui semua tampilan. */
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

    /** Terapkan semua pengaturan tampilan (ikon, kolom, jam, kaca). Dipanggil saat start dan tiap pengaturan berubah. */
    private fun applyStyles() {
        val iconPx = dp(cfg.iconSizeDp)
        val labelColor = LauncherPrefs.LABEL_COLORS[cfg.labelColorIndex]

        drawerAdapter.applyStyle(iconPx, cfg.showLabels, Color.WHITE)
        homeAdapter.applyStyle(iconPx, cfg.showLabels, labelColor)
        (recycler.layoutManager as GridLayoutManager).spanCount = cfg.columns
        (homeRecycler.layoutManager as GridLayoutManager).spanCount = cfg.columns

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
        applyRefreshRate()
    }

    /**
     * Minta layar memakai refresh rate tertinggi (mis. 120 Hz) untuk jendela launcher ini.
     * Tanpa ini, sistem sering membatasi aplikasi pihak ketiga ke 60 Hz atau kurang.
     */
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
                // Aplikasi "membesar" dari posisi ikon yang diketuk
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
        // withLayer(): tampilan digambar sekali ke tekstur GPU lalu cuma digeser/dipudarkan, jauh lebih ringan
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
        // Hanya yang benar-benar terpasang, punya ikon launcher, dan tidak disembunyikan
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

    // ================================================================ ikon layar utama

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

    private fun homePackages(): List<String> {
        val pkgs = cfg.getList("home") ?: defaultHome()
        return pkgs.filter { p -> allApps.any { it.packageName == p } }.take(MAX_HOME)
    }

    private fun renderHome() {
        homeAdapter.submit(homePackages().mapNotNull { pkg -> allApps.find { it.packageName == pkg } })
    }

    private fun addToHome(app: AppInfo) {
        val current = homePackages().toMutableList()
        if (app.packageName in current) {
            Toast.makeText(this, "Sudah ada di layar utama", Toast.LENGTH_SHORT).show()
            return
        }
        if (current.size >= MAX_HOME) {
            Toast.makeText(
                this,
                "Layar utama penuh (maks. $MAX_HOME). Tahan ikon untuk menghapus.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        current.add(app.packageName)
        cfg.setList("home", current)
        renderHome()
        Toast.makeText(this, "${app.label} ditambahkan ke layar utama", Toast.LENGTH_SHORT).show()
    }

    private fun removeFromHome(app: AppInfo) {
        val current = homePackages().toMutableList()
        current.remove(app.packageName)
        cfg.setList("home", current)
        renderHome()
    }

    // ================================================================ widget Android

    private fun renderWidgets() {
        widgetContainer.removeAllViews()
        val ids = cfg.widgetIds
        val valid = mutableListOf<Int>()
        val density = resources.displayMetrics.density

        for (id in ids) {
            val info = appWidgetManager.getAppWidgetInfo(id)
            if (info == null) {
                // Aplikasi widget sudah dihapus
                appWidgetHost.deleteAppWidgetId(id)
                continue
            }
            valid.add(id)

            val heightPx = info.minHeight.coerceIn(dp(72), dp(360))
            val hv = appWidgetHost.createView(this, id, info)
            hv.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(22).toFloat())
                }
            }
            hv.clipToOutline = true

            val wDp = ((resources.displayMetrics.widthPixels - dp(32)) / density).toInt()
            val hDp = (heightPx / density).toInt()
            hv.updateAppWidgetSize(null, wDp, hDp, wDp, hDp)

            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx)
            params.topMargin = dp(12)
            widgetContainer.addView(hv, params)
        }
        if (valid.size != ids.size) cfg.widgetIds = valid
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
                // Minta izin pengguna untuk memasang widget ini
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
        cfg.widgetIds = cfg.widgetIds + id
        pendingWidgetId = -1
        renderWidgets()
        Toast.makeText(this, "Widget ditambahkan", Toast.LENGTH_SHORT).show()
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
        val ids = cfg.widgetIds
        if (ids.isEmpty()) {
            Toast.makeText(this, "Belum ada widget", Toast.LENGTH_SHORT).show()
            return
        }
        val pm = packageManager
        val names = ids.map { id ->
            appWidgetManager.getAppWidgetInfo(id)?.loadLabel(pm)?.toString() ?: "Widget $id"
        }
        AlertDialog.Builder(this)
            .setTitle("Hapus widget")
            .setItems(names.toTypedArray()) { _, which ->
                val id = ids[which]
                try {
                    appWidgetHost.deleteAppWidgetId(id)
                } catch (e: Exception) {
                    // abaikan
                }
                cfg.widgetIds = cfg.widgetIds - id
                renderWidgets()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ================================================================ menu

    private fun showHomeMenu() {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        labels.add("Tambah widget")
        actions.add { showWidgetPicker() }
        if (cfg.widgetIds.isNotEmpty()) {
            labels.add("Hapus widget")
            actions.add { showRemoveWidget() }
        }
        labels.add("Ganti wallpaper")
        actions.add { openWallpaperPicker() }
        labels.add("Pengaturan launcher")
        actions.add { showSettings() }

        AlertDialog.Builder(this)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun showAppMenu(app: AppInfo, fromHome: Boolean) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (fromHome) {
            labels.add("Hapus dari layar utama")
            actions.add { removeFromHome(app) }
        } else {
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
            "Jumlah kolom: ${cfg.columns}",
            "Nama aplikasi: ${onOff(cfg.showLabels, "Tampil", "Sembunyi")}",
            "Warna nama di layar utama: ${LauncherPrefs.LABEL_COLOR_NAMES[cfg.labelColorIndex]}",
            "Pengaturan widget jam",
            "Transparansi kaca: ${LauncherPrefs.GLASS_NAMES[cfg.glassIndex]}",
            "Refresh rate tertinggi: ${onOff(cfg.highRefresh, "Aktif", "Mati")}",
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
                    1 -> chooseOne("Jumlah kolom", arrayOf("4 kolom", "5 kolom"), cfg.columns - 4) {
                        cfg.columns = it + 4
                        applyStyles()
                        showSettings()
                    }
                    2 -> chooseOne("Nama aplikasi", arrayOf("Tampil", "Sembunyi"), if (cfg.showLabels) 0 else 1) {
                        cfg.showLabels = (it == 0)
                        applyStyles()
                        showSettings()
                    }
                    3 -> chooseOne("Warna nama di layar utama", LauncherPrefs.LABEL_COLOR_NAMES, cfg.labelColorIndex) {
                        cfg.labelColorIndex = it
                        applyStyles()
                        showSettings()
                    }
                    4 -> showClockSettings()
                    5 -> chooseOne("Transparansi kaca", LauncherPrefs.GLASS_NAMES, cfg.glassIndex) {
                        cfg.glassIndex = it
                        applyStyles()
                        showSettings()
                    }
                    6 -> chooseOne("Refresh rate tertinggi", arrayOf("Aktif", "Mati"), if (cfg.highRefresh) 0 else 1) {
                        cfg.highRefresh = (it == 0)
                        applyStyles()
                        showSettings()
                    }
                    7 -> showHiddenApps()
                    8 -> confirmReset()
                    9 -> openDefaultLauncherSettings()
                }
            }
            .setNegativeButton("Tutup", null)
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
