package com.gtlauncher.app

import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
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
        const val HOST_ID = 1024
        const val REQ_BIND = 4001
        const val REQ_CONFIGURE = 4002
    }

    /** Satu baris di pemilih widget. */
    private class PickEntry(val title: String, val subtitle: String, val info: AppWidgetProviderInfo?)

    // ---- tampilan
    private lateinit var root: DragLayer
    private lateinit var homeLayer: View
    private lateinit var dock: LinearLayout
    private lateinit var drawerLayer: View
    private lateinit var searchInput: EditText
    private lateinit var recycler: RecyclerView

    // ---- data dan pengaturan
    private lateinit var cfg: LauncherPrefs
    private lateinit var controller: HomeController
    private lateinit var drawerAdapter: AppAdapter
    private lateinit var flingDetector: GestureDetector
    private lateinit var appWidgetManager: AppWidgetManager
    private lateinit var appWidgetHost: AppWidgetHost

    private val executor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    /** Ikon yang sudah diproses disimpan di sini, supaya tidak diproses ulang tiap kembali ke layar utama. */
    private val iconCache = ConcurrentHashMap<String, Drawable>()
    private val widgetViews = HashMap<Int, AppWidgetHostView>()

    private var allAppsRaw: List<AppInfo> = emptyList()
    private var allApps: List<AppInfo> = emptyList()
    private var filtered: List<AppInfo> = emptyList()
    private var drawerOpen = false
    private var receiverRegistered = false
    private var returnAnim = false
    private var pendingWidgetId = -1

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            // Aplikasi sedang diperbarui: abaikan "dihapus" sementara, supaya ikonnya tidak ikut terhapus
            if (intent.action == Intent.ACTION_PACKAGE_REMOVED &&
                intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            ) {
                return
            }
            val pkg = intent.data?.schemeSpecificPart
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
        dock = findViewById(R.id.dock)
        drawerLayer = findViewById(R.id.drawerLayer)
        searchInput = findViewById(R.id.searchInput)
        recycler = findViewById(R.id.recycler)

        // Beri ruang untuk status bar, navigation bar, dan keyboard
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            homeLayer.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            drawerLayer.setPadding(dp(20) + bars.left, bars.top, dp(20) + bars.right, bars.bottom)
            insets
        }

        // Daftar semua aplikasi (Pustaka Aplikasi)
        drawerAdapter = AppAdapter(
            resources,
            { app, view -> launch(app, view) },
            { app, _ -> showAppMenu(app, false) }
        )
        recycler.layoutManager = GridLayoutManager(this, cfg.columns)
        recycler.adapter = drawerAdapter
        recycler.setHasFixedSize(true)
        recycler.itemAnimator = null
        recycler.setItemViewCacheSize(24)

        // Layar utama: halaman, dock, dan mode edit
        val pager = findViewById<ViewPager2>(R.id.pager)
        val dots = findViewById<LinearLayout>(R.id.pageDots)
        val editBar = findViewById<View>(R.id.editBar)
        controller = HomeController(this, cfg, root, pager, dots, dock, editBar)

        findViewById<View>(R.id.editWidget).setOnClickListener { showWidgetPicker() }
        findViewById<View>(R.id.editSettings).setOnClickListener { showSettings() }
        findViewById<View>(R.id.editLock).setOnClickListener {
            cfg.layoutLocked = true
            controller.exitEdit()
            toast("Tata letak dikunci. Buka kuncinya di Pengaturan.")
        }
        findViewById<View>(R.id.editDone).setOnClickListener { controller.exitEdit() }

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
        // Tombol Home ditekan saat launcher sudah terbuka -> kembali ke layar utama pertama
        if (intent != null &&
            intent.action == Intent.ACTION_MAIN &&
            intent.hasCategory(Intent.CATEGORY_HOME)
        ) {
            closeDrawer()
            controller.handleHome()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Launcher tidak boleh menutup dirinya sendiri
        if (controller.handleBack()) return
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
                if (Math.abs(dy) < Math.abs(dx)) return false // geseran mendatar (ganti halaman), abaikan

                if (drawerOpen) {
                    // Geser cepat ke bawah saat daftar sudah di paling atas -> tutup
                    if (velocityY > 2000f && dy > dp(80) && !recycler.canScrollVertically(-1)) {
                        closeDrawer()
                        return true
                    }
                    return false
                }

                if (controller.isBusy() || controller.isEditing()) return false
                if (controller.isWidgetAt(e1.rawX, e1.rawY)) return false

                if (velocityY < -1200f && dy < -dp(80)) {
                    openDrawer()
                    return true
                } else if (velocityY > 1500f && dy > dp(80)) {
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
        controller.setApps(allApps, allAppsRaw.map { it.key }.toSet())
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

    /** Terapkan semua pengaturan tampilan. Dipanggil saat start dan tiap pengaturan berubah. */
    private fun applyStyles() {
        drawerAdapter.applyStyle(dp(cfg.iconSizeDp), cfg.showLabels, Color.WHITE)
        (recycler.layoutManager as GridLayoutManager).spanCount = cfg.columns
        controller.applyStyle()
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

    fun launch(app: AppInfo, source: View?) {
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
            controller.onAppLaunched(app.key)
        } catch (e: Exception) {
            toast("Tidak bisa membuka ${app.label}")
        }
        if (drawerOpen) closeDrawer(immediate = true)
    }

    fun openClockApp() {
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

    // ================================================================ aplikasi bawaan untuk layar utama dan dock

    private fun resolvePackages(intents: List<Intent>): List<String> =
        intents.mapNotNull {
            packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.distinct()

    fun defaultDockPackages(): List<String> = resolvePackages(
        listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        )
    )

    fun defaultHomePackages(): List<String> {
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

    // ================================================================ widget Android

    fun widgetInfo(id: Int): AppWidgetProviderInfo? =
        try {
            appWidgetManager.getAppWidgetInfo(id)
        } catch (e: Exception) {
            null
        }

    fun widgetView(item: HomeItem): AppWidgetHostView? {
        val id = item.widgetId
        val cached = widgetViews[id]
        if (cached != null) return cached
        val info = widgetInfo(id) ?: return null
        return try {
            val hv = appWidgetHost.createView(this, id, info)
            widgetViews[id] = hv
            hv
        } catch (e: Exception) {
            null
        }
    }

    fun updateWidgetSize(hv: AppWidgetHostView, wPx: Int, hPx: Int) {
        if (wPx <= 0 || hPx <= 0) return
        val d = resources.displayMetrics.density
        val wDp = (wPx / d).toInt()
        val hDp = (hPx / d).toInt()
        try {
            hv.updateAppWidgetSize(null, wDp, hDp, wDp, hDp)
        } catch (e: Exception) {
            // abaikan
        }
    }

    fun deleteWidget(id: Int) {
        widgetViews.remove(id)
        try {
            appWidgetHost.deleteAppWidgetId(id)
        } catch (e: Exception) {
            // abaikan
        }
    }

    private fun buildPickRow(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(16), dp(10), dp(16), dp(10))

        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        val bg = GradientDrawable()
        bg.cornerRadius = dp(12).toFloat()
        bg.setColor(0x22808080)
        iv.background = bg
        iv.setPadding(dp(6), dp(6), dp(6), dp(6))
        row.addView(iv, LinearLayout.LayoutParams(dp(104), dp(76)))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val title = TextView(this)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        val sub = TextView(this)
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        sub.alpha = 0.7f
        col.addView(title)
        col.addView(sub)
        val colLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        colLp.leftMargin = dp(14)
        row.addView(col, colLp)
        return row
    }

    private fun previewFor(e: PickEntry): Drawable? {
        val info = e.info ?: return getDrawable(R.drawable.bg_widget)
        return try {
            info.loadPreviewImage(this, 0) ?: info.loadIcon(this, 0)
        } catch (ex: Exception) {
            null
        }
    }

    /** Pemilih widget dengan gambar pratinjau. */
    fun showWidgetPicker() {
        val pm = packageManager
        val raw = try {
            appWidgetManager.installedProviders
        } catch (e: Exception) {
            emptyList<AppWidgetProviderInfo>()
        }
        val entries = ArrayList<PickEntry>()
        if (!controller.hasClock()) {
            entries.add(PickEntry("Jam dan tanggal", "GT Launcher • bawaan", null))
        }
        val sorted = raw.sortedBy { (it.loadLabel(pm) ?: "").lowercase() }
        for (info in sorted) {
            val app = try {
                pm.getApplicationInfo(info.provider.packageName, 0).loadLabel(pm).toString()
            } catch (e: Exception) {
                info.provider.packageName
            }
            val s = controller.spanFor(info)
            entries.add(PickEntry(info.loadLabel(pm) ?: "Widget", "$app • ${s[0]}×${s[1]}", info))
        }
        if (entries.isEmpty()) {
            toast("Tidak ada widget tersedia")
            return
        }

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = entries.size

            override fun getItem(position: Int): Any = entries[position]

            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row = (convertView as? LinearLayout) ?: buildPickRow()
                val e = entries[position]
                val iv = row.getChildAt(0) as ImageView
                val col = row.getChildAt(1) as LinearLayout
                (col.getChildAt(0) as TextView).text = e.title
                (col.getChildAt(1) as TextView).text = e.subtitle
                iv.setImageDrawable(previewFor(e))
                return row
            }
        }
        val lv = ListView(this)
        lv.adapter = adapter
        val dialog = AlertDialog.Builder(this)
            .setTitle("Pilih widget")
            .setView(lv)
            .setNegativeButton("Batal", null)
            .create()
        lv.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            val e = entries[position]
            if (e.info == null) {
                if (controller.addClockWidget()) toast("Jam ditambahkan") else toast("Tidak ada ruang kosong")
            } else {
                addWidget(e.info)
            }
        }
        dialog.show()
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
            toast("Widget ini tidak bisa dipasang")
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
        val info = widgetInfo(id)
        pendingWidgetId = -1
        if (info != null && controller.addWidget(id, info)) {
            toast("Widget ditambahkan")
        } else {
            deleteWidget(id)
            toast("Tidak ada ruang kosong di layar utama")
        }
    }

    private fun cancelPendingWidget() {
        if (pendingWidgetId != -1) {
            deleteWidget(pendingWidgetId)
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
                    val info = widgetInfo(id)
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

    // ================================================================ menu

    fun showHomeMenu() {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (!cfg.layoutLocked) {
            labels.add("Edit layar utama")
            actions.add { controller.enterEdit() }
        }
        labels.add("Tambah widget")
        actions.add { showWidgetPicker() }
        labels.add("Ganti wallpaper")
        actions.add { openWallpaperPicker() }
        labels.add(if (cfg.layoutLocked) "Buka kunci tata letak" else "Kunci tata letak")
        actions.add {
            cfg.layoutLocked = !cfg.layoutLocked
            if (cfg.layoutLocked) controller.exitEdit()
            toast(if (cfg.layoutLocked) "Tata letak dikunci" else "Kunci tata letak dibuka")
        }
        labels.add("Pengaturan launcher")
        actions.add { showSettings() }

        AlertDialog.Builder(this)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    fun showAppMenu(app: AppInfo, fromHome: Boolean) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (fromHome) {
            if (!cfg.layoutLocked) {
                labels.add("Hapus dari layar utama")
                actions.add { controller.removeApp(app.key) }
            }
        } else {
            labels.add("Tambah ke layar utama")
            actions.add {
                when (controller.addAppToHome(app)) {
                    0 -> toast("${app.label} ditambahkan ke layar utama")
                    1 -> toast("Sudah ada di layar utama atau dock")
                    else -> toast("Layar utama penuh")
                }
            }
            labels.add("Tambah ke dock")
            actions.add {
                when (controller.addAppToDock(app)) {
                    0 -> toast("${app.label} ditambahkan ke dock")
                    1 -> toast("Sudah ada di layar utama atau dock")
                    else -> toast("Dock penuh")
                }
            }
            labels.add("Sembunyikan aplikasi")
            actions.add {
                cfg.hidden = cfg.hidden + app.packageName
                rebuildVisible()
                toast("${app.label} disembunyikan (bisa ditampilkan lagi di Pengaturan)")
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
                    toast("Aksi tidak tersedia")
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
            toast("Tidak bisa membuka pengaturan wallpaper")
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

    fun showSettings() {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        labels.add("Ukuran grid layar utama: ${controller.gridCols()}×${controller.gridRows()}")
        actions.add {
            chooseOne("Jumlah kolom", arrayOf("4 kolom", "5 kolom", "6 kolom"), controller.gridCols() - 4) { c ->
                chooseOne("Jumlah baris", arrayOf("5 baris", "6 baris", "7 baris"), controller.gridRows() - 5) { r ->
                    cfg.gridCols = c + 4
                    cfg.gridRows = r + 5
                    controller.setGrid(c + 4, r + 5)
                    showSettings()
                }
            }
        }

        labels.add("Kunci tata letak: ${onOff(cfg.layoutLocked, "Aktif", "Mati")}")
        actions.add {
            chooseOne("Kunci tata letak", arrayOf("Aktif", "Mati"), if (cfg.layoutLocked) 0 else 1) {
                cfg.layoutLocked = (it == 0)
                if (cfg.layoutLocked) controller.exitEdit()
                showSettings()
            }
        }

        labels.add("Aplikasi baru masuk layar utama: ${onOff(cfg.autoAddNew, "Otomatis", "Tidak")}")
        actions.add {
            chooseOne("Aplikasi baru dipasang", arrayOf("Otomatis masuk layar utama", "Jangan tambahkan"), if (cfg.autoAddNew) 0 else 1) {
                cfg.autoAddNew = (it == 0)
                showSettings()
            }
        }

        labels.add("Kolom Pustaka Aplikasi: ${cfg.columns}")
        actions.add {
            chooseOne("Kolom Pustaka Aplikasi", arrayOf("4 kolom", "5 kolom"), cfg.columns - 4) {
                cfg.columns = it + 4
                applyStyles()
                showSettings()
            }
        }

        labels.add("Ukuran ikon: ${LauncherPrefs.ICON_SIZE_NAMES[cfg.iconSizeIndex]}")
        actions.add {
            chooseOne("Ukuran ikon", LauncherPrefs.ICON_SIZE_NAMES, cfg.iconSizeIndex) {
                cfg.iconSizeIndex = it
                applyStyles()
                showSettings()
            }
        }

        labels.add("Nama aplikasi: ${onOff(cfg.showLabels, "Tampil", "Sembunyi")}")
        actions.add {
            chooseOne("Nama aplikasi", arrayOf("Tampil", "Sembunyi"), if (cfg.showLabels) 0 else 1) {
                cfg.showLabels = (it == 0)
                applyStyles()
                showSettings()
            }
        }

        labels.add("Warna nama di layar utama: ${LauncherPrefs.LABEL_COLOR_NAMES[cfg.labelColorIndex]}")
        actions.add {
            chooseOne("Warna nama di layar utama", LauncherPrefs.LABEL_COLOR_NAMES, cfg.labelColorIndex) {
                cfg.labelColorIndex = it
                applyStyles()
                showSettings()
            }
        }

        labels.add("Pengaturan widget jam")
        actions.add { showClockSettings() }

        labels.add("Transparansi kaca: ${LauncherPrefs.GLASS_NAMES[cfg.glassIndex]}")
        actions.add {
            chooseOne("Transparansi kaca", LauncherPrefs.GLASS_NAMES, cfg.glassIndex) {
                cfg.glassIndex = it
                applyStyles()
                showSettings()
            }
        }

        labels.add("Refresh rate tertinggi: ${onOff(cfg.highRefresh, "Aktif", "Mati")}")
        actions.add {
            chooseOne("Refresh rate tertinggi", arrayOf("Aktif", "Mati"), if (cfg.highRefresh) 0 else 1) {
                cfg.highRefresh = (it == 0)
                applyStyles()
                showSettings()
            }
        }

        labels.add("Aplikasi tersembunyi (${cfg.hidden.size})")
        actions.add { showHiddenApps() }

        labels.add("Atur ulang layar utama dan dock")
        actions.add { confirmReset() }

        labels.add("Jadikan launcher default")
        actions.add { openDefaultLauncherSettings() }

        AlertDialog.Builder(this)
            .setTitle("Pengaturan GT Launcher")
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
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
                        controller.setClockVisible(it == 0)
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
            toast("Tidak ada aplikasi tersembunyi")
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
            .setMessage("Isi layar utama dan dock dikembalikan ke bawaan. Widget tetap dipertahankan.")
            .setPositiveButton("Atur ulang") { _, _ ->
                controller.resetToDefault()
                toast("Layar utama dan dock diatur ulang")
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
                toast("Tidak bisa membuka pengaturan")
            }
        }
    }

    // ================================================================ util

    fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = dpToPx(value)
}
