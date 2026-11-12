package com.gtlauncher.app

import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
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
import android.view.DragEvent
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
        const val REQ_CUSTOM_ICON = 4101
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
    private lateinit var btnDoneEdit: TextView
    private lateinit var drawerLayer: View
    private lateinit var searchInput: EditText
    private lateinit var recycler: RecyclerView
    private lateinit var drawerTitle: TextView
    private lateinit var btnSort: TextView
    private lateinit var btnLibrarySettings: TextView
    private lateinit var categoryScroll: HorizontalScrollView
    private lateinit var categoryRow: LinearLayout
    private lateinit var recentSection: LinearLayout
    private lateinit var recentRow: LinearLayout
    private lateinit var frequentSection: LinearLayout
    private lateinit var frequentRow: LinearLayout
    private lateinit var recentLabel: TextView
    private lateinit var frequentLabel: TextView
    private lateinit var listFrame: FrameLayout
    private lateinit var emptyView: TextView
    private lateinit var letterIndex: AlphabetIndexView
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionCount: TextView
    private lateinit var btnSelAdd: TextView
    private lateinit var btnSelHide: TextView
    private lateinit var btnSelDelete: TextView
    private lateinit var btnSelCancel: TextView

    // ---- data dan pengaturan
    private lateinit var cfg: LauncherPrefs
    private lateinit var drawerAdapter: AppAdapter
    private lateinit var homePagerAdapter: HomePageAdapter
    private lateinit var flingDetector: GestureDetector
    private lateinit var appWidgetManager: AppWidgetManager
    private lateinit var appWidgetHost: AppWidgetHost

    private val executor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val edgeScrollHandler = Handler(Looper.getMainLooper())
    private var edgeScrollRunnable: Runnable? = null

    /** Ikon yang sudah diproses disimpan di sini. */
    private val iconCache = ConcurrentHashMap<String, Drawable>()

    private var allAppsRaw: List<AppInfo> = emptyList()
    private var allApps: List<AppInfo> = emptyList()
    private var filtered: List<AppInfo> = emptyList()
    private var homeItems: MutableList<LauncherItem> = mutableListOf()
    private var dockItems: MutableList<LauncherItem> = mutableListOf()

    private var isEditMode = false
    private var drawerOpen = false
    private var receiverRegistered = false
    private var returnAnim = false
    private var pendingWidgetId = -1

    private var currentCategory = 0
    private var selectionMode = false
    private val selectedPackages = mutableSetOf<String>()
    private var pendingCustomIconPackage = ""

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            val pkg = intent?.data?.schemeSpecificPart
            if (pkg != null) {
                iconCache.keys.toList().forEach { key ->
                    if (key.startsWith("$pkg/")) iconCache.remove(key)
                }

                if (action == Intent.ACTION_PACKAGE_REMOVED && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    homeItems.removeAll { it.type == ItemType.APP && it.packageName == pkg }
                    homeItems.forEach { folder ->
                        if (folder.type == ItemType.FOLDER) {
                            folder.folderItems.removeAll { it.packageName == pkg }
                        }
                    }
                    dockItems.removeAll { it.type == ItemType.APP && it.packageName == pkg }
                    dockItems.forEach { folder ->
                        if (folder.type == ItemType.FOLDER) {
                            folder.folderItems.removeAll { it.packageName == pkg }
                        }
                    }
                    cfg.setHomeItems(homeItems)
                    cfg.setDockItems(dockItems)
                } else if (action == Intent.ACTION_PACKAGE_ADDED && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
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
        btnDoneEdit = findViewById(R.id.btnDoneEdit)
        drawerLayer = findViewById(R.id.drawerLayer)
        searchInput = findViewById(R.id.searchInput)
        recycler = findViewById(R.id.recycler)
        drawerTitle = findViewById(R.id.drawerTitle)
        btnSort = findViewById(R.id.btnSort)
        btnLibrarySettings = findViewById(R.id.btnLibrarySettings)
        categoryScroll = findViewById(R.id.categoryScroll)
        categoryRow = findViewById(R.id.categoryRow)
        recentSection = findViewById(R.id.recentSection)
        recentRow = findViewById(R.id.recentRow)
        frequentSection = findViewById(R.id.frequentSection)
        frequentRow = findViewById(R.id.frequentRow)
        recentLabel = findViewById(R.id.recentLabel)
        frequentLabel = findViewById(R.id.frequentLabel)
        listFrame = findViewById(R.id.listFrame)
        emptyView = findViewById(R.id.emptyView)
        letterIndex = findViewById(R.id.letterIndex)
        selectionBar = findViewById(R.id.selectionBar)
        selectionCount = findViewById(R.id.selectionCount)
        btnSelAdd = findViewById(R.id.btnSelAdd)
        btnSelHide = findViewById(R.id.btnSelHide)
        btnSelDelete = findViewById(R.id.btnSelDelete)
        btnSelCancel = findViewById(R.id.btnSelCancel)

        btnDoneEdit.setOnClickListener { exitEditMode() }

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
            onClick = { app, view ->
                if (selectionMode) toggleSelection(app.packageName)
                else launch(app, view)
            },
            onLongClick = { app, _ ->
                if (selectionMode) toggleSelection(app.packageName)
                else showAppMenu(app, fromHome = false)
            }
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
            onItemClick = { _, app, view ->
                if (app != null) launch(app, view)
            },
            onItemLongClick = { item, app, _ ->
                if (!cfg.lockLayout) {
                    enterEditMode()
                } else if (app != null) {
                    showHomeItemMenu(item, app)
                }
            },
            onPageLongClick = { _ ->
                if (!cfg.lockLayout && !isEditMode) {
                    enterEditMode()
                } else {
                    showHomeMenu()
                }
            },
            onDeleteClick = { item -> confirmDeleteItem(item) },
            onFolderClick = { folder, _ -> showFolderDialog(folder) },
            onItemStartDrag = { item, view -> startDragItem(item, view) },
            onCellDrag = { page, cellLayout, event -> handleCellDrag(page, cellLayout, event) },
            onWidgetCreateView = { wid -> createWidgetView(wid) },
            onWidgetResize = { item -> showWidgetResize(item) },
            getIconPx = { dp(cfg.iconSizeDp) },
            getShowLabels = { cfg.showLabels },
            getLabelColor = { LauncherPrefs.LABEL_COLORS[cfg.labelColorIndex] }
        )
        homePager.adapter = homePagerAdapter
        pageIndicator.attachToViewPager(homePager)

        // Setup drag listener pada dock
        setupDockDrag()

        // Ketuk widget jam -> buka aplikasi jam. Tahan area kosong -> menu layar utama / edit mode.
        widget.setOnClickListener { openClockApp() }
        widget.setOnLongClickListener {
            if (!cfg.lockLayout) enterEditMode() else showHomeMenu()
            true
        }
        homeLayer.setOnLongClickListener {
            if (!cfg.lockLayout) enterEditMode() else showHomeMenu()
            true
        }

        setupGestures()
        setupSearch()
        setupLibrary()
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
            // beberapa perangkat menolak, abaikan
        }
        loadApps()
    }

    override fun onResume() {
        super.onResume()
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
        if (drawerOpen && ::recentRow.isInitialized) {
            refreshLibrary()
            refreshQuickRows()
        }
    }

    override fun onStop() {
        super.onStop()
        cancelEdgeScroll()
        try {
            appWidgetHost.stopListening()
        } catch (e: Exception) {
            // abaikan
        }
        if (receiverRegistered) {
            try {
                unregisterReceiver(packageReceiver)
            } catch (e: IllegalArgumentException) {
                // abaikan
            }
            receiverRegistered = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelEdgeScroll()
        executor.shutdown()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null &&
            intent.action == Intent.ACTION_MAIN &&
            intent.hasCategory(Intent.CATEGORY_HOME)
        ) {
            if (isEditMode) exitEditMode()
            closeDrawer()
            if (::homePager.isInitialized && homePager.currentItem != 0) {
                homePager.setCurrentItem(0, true)
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (drawerOpen) {
            closeDrawer()
        } else if (isEditMode) {
            exitEditMode()
        }
    }

    // ================================================================ mode edit ala iOS

    private fun enterEditMode() {
        if (cfg.lockLayout) {
            Toast.makeText(this, "Tata letak terkunci", Toast.LENGTH_SHORT).show()
            return
        }
        isEditMode = true
        btnDoneEdit.visibility = View.VISIBLE
        homePagerAdapter.isEditMode = true
        renderDock()
    }

    private fun exitEditMode() {
        isEditMode = false
        btnDoneEdit.visibility = View.GONE
        homePagerAdapter.isEditMode = false
        renderDock()
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
                if (Math.abs(dy) < Math.abs(dx)) return false

                if (drawerOpen) {
                    if (velocityY > 2000f && dy > dp(80) && !recycler.canScrollVertically(-1)) {
                        closeDrawer()
                        return true
                    }
                    return false
                }

                if (isEditMode) return false

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
        searchInput.setOnEditorActionListener { _, _, _ ->
            filtered.firstOrNull()?.let { launch(it, null) }
            true
        }
    }

    // ================================================================ data aplikasi

    private fun reloadApps() {
        iconCache.clear()
        loadApps()
    }

    private fun loadApps() {
        executor.execute {
            val pm = packageManager
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val raw = pm.queryIntentActivities(query, 0)
                .filter { it.activityInfo.packageName != packageName }
                .mapNotNull { resolveInfo ->
                    try {
                        val pkg = resolveInfo.activityInfo.packageName
                        val comp = ComponentName(pkg, resolveInfo.activityInfo.name)
                        val key = comp.flattenToString()
                        val customIcon = cfg.customIcons[pkg]
                        val icon = if (!customIcon.isNullOrEmpty()) {
                            iconCache[key] ?: loadCustomIcon(customIcon)?.also { iconCache[key] = it }
                                ?: resolveInfo.loadIcon(pm).toIosBitmapDrawable(this@MainActivity)
                        } else {
                            iconCache[key] ?: try {
                                resolveInfo.loadIcon(pm).toIosBitmapDrawable(this@MainActivity)
                            } catch (e: Exception) {
                                pm.defaultActivityIcon.toIosBitmapDrawable(this@MainActivity)
                            }.also { d -> iconCache[key] = d }
                        }

                        val appInfo = try {
                            pm.getApplicationInfo(pkg, 0)
                        } catch (e: Exception) {
                            null
                        }
                        val category = appInfo?.category ?: 0
                        val label = cfg.customLabels[pkg] ?: try {
                            resolveInfo.loadLabel(pm).toString()
                        } catch (e: Exception) {
                            pkg
                        }
                        AppInfo(
                            label = label,
                            packageName = pkg,
                            component = comp,
                            icon = icon,
                            category = category,
                            isGame = AppClassifier.isGame(pkg, label, category),
                            installTime = appInfo?.let {
                                try {
                                    pm.getPackageInfo(pkg, 0).firstInstallTime
                                } catch (e: Exception) {
                                    0L
                                }
                            } ?: 0L
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                .sortedBy { it.label.lowercase() }

            UsageStatsHelper.enrich(this, raw)

            ui.post {
                if (isFinishing || isDestroyed) return@post
                allAppsRaw = raw
                rebuildVisible()
            }
        }
    }

    private fun loadCustomIcon(uriString: String): Drawable? {
        return try {
            contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
                val bitmap = android.graphics.BitmapFactory.decodeStream(input) ?: return null
                android.graphics.drawable.BitmapDrawable(resources, bitmap)
                    .toIosBitmapDrawable(this)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun rebuildVisible() {
        val hidden = cfg.hidden
        allApps = allAppsRaw.filter { it.packageName !in hidden }
        if (selectionMode) {
            val available = allApps.map { it.packageName }.toSet()
            selectedPackages.retainAll(available)
            if (selectedPackages.isEmpty()) exitSelection()
        }
        refreshLibrary()
        renderHome()
        renderDock()
        refreshQuickRows()
    }

    private fun applyFilter() {
        refreshLibrary()
    }

    private fun setupLibrary() {
        btnSort.setOnClickListener {
            chooseOne(
                "Urutkan aplikasi",
                LauncherPrefs.SORT_NAMES,
                cfg.librarySort
            ) {
                cfg.librarySort = it
                refreshLibrary()
            }
        }
        btnLibrarySettings.setOnClickListener { showLibrarySettings() }
        letterIndex.onLetterSelected = { letter -> scrollToLetter(letter) }

        btnSelAdd.setOnClickListener {
            for (pkg in selectedPackages.toList()) {
                allApps.find { it.packageName == pkg }?.let { addToHome(it) }
            }
            exitSelection()
        }
        btnSelHide.setOnClickListener {
            cfg.hidden = cfg.hidden + selectedPackages
            exitSelection()
            rebuildVisible()
            Toast.makeText(this, "Aplikasi disembunyikan", Toast.LENGTH_SHORT).show()
        }
        btnSelDelete.setOnClickListener {
            val pkgs = selectedPackages.toList()
            val pkg = pkgs.firstOrNull()
            if (pkg != null) {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)))
                if (pkgs.size > 1) {
                    Toast.makeText(this, "Copot satu per satu untuk aplikasi lain", Toast.LENGTH_SHORT).show()
                }
            }
            exitSelection()
        }
        btnSelCancel.setOnClickListener { exitSelection() }
    }

    private fun buildCategoryChips() {
        if (!::categoryRow.isInitialized) return
        categoryRow.removeAllViews()
        if (!cfg.showCategories) {
            categoryScroll.visibility = View.GONE
            return
        }
        categoryScroll.visibility = View.VISIBLE
        for (i in LauncherPrefs.CATEGORY_NAMES.indices) {
            val chip = TextView(this).apply {
                text = LauncherPrefs.CATEGORY_NAMES[i]
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(32)
                ).apply { marginEnd = dp(6) }
                isClickable = true
                setOnClickListener {
                    currentCategory = i
                    refreshLibrary()
                    buildCategoryChips()
                }
            }
            if (i == currentCategory) {
                chip.setBackgroundResource(R.drawable.bg_chip_selected)
                chip.setTextColor(Color.WHITE)
            } else {
                chip.setBackgroundResource(R.drawable.bg_chip)
                chip.setTextColor(Color.WHITE)
            }
            categoryRow.addView(chip)
        }
    }

    private fun refreshLibrary() {
        if (!::searchInput.isInitialized) return
        val query = searchInput.text.toString().trim()
        var list = allApps.filter {
            (currentCategory == 0 || AppClassifier.categoryIndex(it) == currentCategory) &&
                (query.isEmpty() || it.label.contains(query, ignoreCase = true))
        }
        list = when (cfg.librarySort) {
            1 -> list.sortedByDescending { it.installTime }
            2 -> list.sortedByDescending { it.usageWeekMs }
            else -> list.sortedBy { it.label.lowercase() }
        }
        filtered = list
        drawerAdapter.submit(list)
        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        btnSort.text = LauncherPrefs.SORT_NAMES[cfg.librarySort]
        updateLetterIndexVisibility()
        val showMeta = query.isEmpty() && !selectionMode && UsageStatsHelper.hasAccess(this)
        recentSection.visibility = if (cfg.showRecentRow && showMeta) View.VISIBLE else View.GONE
        frequentSection.visibility = if (cfg.showFrequentRow && showMeta) View.VISIBLE else View.GONE
        if (showMeta) refreshQuickRows()
    }

    private fun updateLetterIndexVisibility() {
        val query = if (::searchInput.isInitialized) searchInput.text.toString().trim() else ""
        letterIndex.visibility = if (
            cfg.showLetterIndex && !selectionMode && query.isEmpty() && cfg.librarySort == 0
        ) View.VISIBLE else View.GONE
    }

    private fun scrollToLetter(letter: Char) {
        if (filtered.isEmpty()) return
        val idx = filtered.indexOfFirst {
            if (letter == '#') {
                val first = it.label.firstOrNull()
                first == null || !first.isLetter()
            } else {
                it.label.firstOrNull()?.uppercaseChar() == letter
            }
        }
        if (idx >= 0) {
            (recycler.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(idx, 0)
        }
    }

    private fun refreshQuickRows() {
        if (!::recentRow.isInitialized) return
        recentRow.removeAllViews()
        frequentRow.removeAllViews()
        if (!UsageStatsHelper.hasAccess(this)) return
        val recent = UsageStatsHelper.recent(this, 5).mapNotNull { entry ->
            allApps.find { it.packageName == entry.packageName }
        }
        val frequent = UsageStatsHelper.frequent(this, 5).mapNotNull { entry ->
            allApps.find { it.packageName == entry.packageName }
        }
        buildQuickRow(recentRow, recent)
        buildQuickRow(frequentRow, frequent)
    }

    private fun buildQuickRow(container: LinearLayout, apps: List<AppInfo>) {
        container.removeAllViews()
        if (apps.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Belum ada data"
                setTextColor(Color.parseColor("#99FFFFFF"))
                textSize = 11f
            })
            return
        }
        for (app in apps.take(4)) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { launch(app, null) }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            cell.addView(ImageView(this).apply {
                setImageDrawable(app.icon)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            })
            cell.addView(TextView(this).apply {
                text = app.label
                textSize = 9.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(0, dp(3), 0, 0)
            })
            container.addView(cell)
        }
    }

    private fun enterSelection(packageName: String) {
        selectionMode = true
        selectedPackages.clear()
        selectedPackages.add(packageName)
        drawerAdapter.selectionMode = true
        drawerAdapter.selected = selectedPackages
        drawerAdapter.notifyDataSetChanged()
        selectionBar.visibility = View.VISIBLE
        updateSelectionBar()
        updateLetterIndexVisibility()
    }

    private fun toggleSelection(packageName: String) {
        if (selectedPackages.contains(packageName)) selectedPackages.remove(packageName)
        else selectedPackages.add(packageName)
        if (selectedPackages.isEmpty()) {
            exitSelection()
            return
        }
        drawerAdapter.selected = selectedPackages
        drawerAdapter.notifyDataSetChanged()
        updateSelectionBar()
    }

    private fun exitSelection() {
        selectionMode = false
        selectedPackages.clear()
        if (::drawerAdapter.isInitialized) {
            drawerAdapter.selectionMode = false
            drawerAdapter.selected = emptySet()
            drawerAdapter.notifyDataSetChanged()
        }
        if (::selectionBar.isInitialized) selectionBar.visibility = View.GONE
        updateLetterIndexVisibility()
    }

    private fun updateSelectionBar() {
        selectionCount.text = "${selectedPackages.size} dipilih"
    }

    // ================================================================ tampilan dan pengaturan

    private fun applyStyles() {
        val iconPx = dp(cfg.iconSizeDp)
        val labelColor = LauncherPrefs.LABEL_COLORS[cfg.labelColorIndex]

        drawerAdapter.applyStyle(iconPx, cfg.showLabels, Color.WHITE)
        (recycler.layoutManager as GridLayoutManager).spanCount = cfg.columns
        drawerTitle.setTextColor(Color.WHITE)
        btnSort.setTextColor(Color.WHITE)
        btnLibrarySettings.setTextColor(Color.WHITE)
        recentLabel.setTextColor(Color.parseColor("#99FFFFFF"))
        frequentLabel.setTextColor(Color.parseColor("#99FFFFFF"))
        emptyView.setTextColor(Color.parseColor("#99FFFFFF"))
        letterIndex.setTextColor(Color.parseColor("#99FFFFFF"))
        buildCategoryChips()

        hintView.setTextColor((labelColor and 0x00FFFFFF) or 0xB3000000.toInt())

        widget.visibility = if (cfg.clockVisible) View.VISIBLE else View.GONE
        val fmt = if (cfg.clock24) "HH:mm" else "h:mm"
        clockView.format24Hour = fmt
        clockView.format12Hour = fmt
        val clockColor = LauncherPrefs.CLOCK_COLORS[cfg.clockColorIndex]
        clockView.setTextColor(clockColor)
        dateView.setTextColor((clockColor and 0x00FFFFFF) or 0xCC000000.toInt())
        dateView.visibility = if (cfg.showDate) View.VISIBLE else View.GONE

        val fill = Color.argb(LauncherPrefs.GLASS_ALPHAS[cfg.glassIndex], 255, 255, 255)
        (widget.background.mutate() as? GradientDrawable)?.setColor(fill)
        (dock.background.mutate() as? GradientDrawable)?.setColor(fill)

        renderDock()
        renderHome()
        refreshLibrary()
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
        if (isEditMode) exitEditMode()
        drawerOpen = true
        drawerLayer.visibility = View.VISIBLE
        drawerLayer.alpha = 0f
        drawerLayer.translationY = dp(60).toFloat()
        drawerLayer.animate().alpha(1f).translationY(0f).setDuration(220).withLayer().start()
        homeLayer.animate().alpha(0f).setDuration(180).withLayer().start()
        refreshLibrary()
        refreshQuickRows()
        buildCategoryChips()
    }

    private fun closeDrawer(immediate: Boolean = false) {
        if (!drawerOpen) return
        drawerOpen = false
        exitSelection()
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

    private fun defaultDock(): List<String> = resolvePackages(
        listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        )
    )

    private fun renderDock() {
        if (dockItems.isEmpty()) {
            dockItems = cfg.migrateOldDockIfNeeded { defaultDock() }.toMutableList()
        }

        dock.removeAllViews()
        val size = dp(cfg.iconSizeDp)

        for (item in dockItems.take(MAX_DOCK)) {
            val cell = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                clipChildren = false
                clipToPadding = false
            }

            val iv = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            }

            if (item.type == ItemType.APP) {
                val app = allApps.find { it.packageName == item.packageName }
                if (app != null) {
                    iv.setImageDrawable(app.icon.constantState?.newDrawable(resources) ?: app.icon)
                    if (!isEditMode) {
                        iv.pressEffect()
                        iv.setOnClickListener { launch(app, iv) }
                        iv.setOnLongClickListener {
                            if (!cfg.lockLayout) enterEditMode() else removeFromDock(item)
                            true
                        }
                    }
                }
            } else if (item.type == ItemType.FOLDER) {
                val childIcons = item.folderItems.mapNotNull { child ->
                    allApps.find { it.packageName == child.packageName }?.icon
                }
                iv.setImageDrawable(createFolderIcon(this, childIcons, size))
                if (!isEditMode) {
                    iv.pressEffect()
                    iv.setOnClickListener { showFolderDialog(item) }
                    iv.setOnLongClickListener {
                        if (!cfg.lockLayout) enterEditMode()
                        true
                    }
                }
            }

            if (isEditMode) {
                cell.startJiggle(dockItems.indexOf(item) * 11)
                val badge = ImageView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                        setMargins(0, 0, dp(26), 0)
                    }
                    setBackgroundResource(R.drawable.bg_badge_delete)
                    setImageResource(R.drawable.ic_minus)
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                    setOnClickListener { confirmDeleteItem(item) }
                }
                cell.addView(badge)
                cell.setOnLongClickListener {
                    startDragItem(item, cell)
                }
            } else {
                cell.stopJiggle()
            }

            cell.addView(iv)
            cell.tag = item
            dock.addView(cell)
        }

        // Tombol Pustaka Aplikasi di dock jika diaktifkan di pengaturan
        if (cfg.showDrawerButton) {
            val cell = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val iv = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
                setImageResource(R.drawable.ic_drawer)
                pressEffect()
                setOnClickListener { openDrawer() }
            }
            cell.addView(iv)
            dock.addView(cell)
        }
    }

    private fun setupDockDrag() {
        dock.setOnDragListener { _, event ->
            val draggedItem = event.localState as? LauncherItem ?: return@setOnDragListener false
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DROP -> handleDockDrop(event, draggedItem)
                else -> true
            }
        }
    }

    private fun handleDockDrop(event: DragEvent, draggedItem: LauncherItem): Boolean {
        cancelEdgeScroll()
        if (cfg.lockLayout) return false
        if (draggedItem.type != ItemType.APP && draggedItem.type != ItemType.FOLDER) {
            Toast.makeText(this, "Hanya aplikasi dan folder yang bisa di dock", Toast.LENGTH_SHORT).show()
            return false
        }

        val dockWidth = dock.width.toFloat().coerceAtLeast(1f)
        val dropX = event.x
        val slotIndex = ((dropX / dockWidth) * MAX_DOCK).toInt().coerceIn(0, MAX_DOCK - 1)

        val targetItem = dockItems.getOrNull(slotIndex)

        // Drop di atas item dock lain -> buat folder di dock
        if (draggedItem.type == ItemType.APP && targetItem != null && targetItem.type == ItemType.APP && targetItem.id != draggedItem.id) {
            homeItems.removeAll { it.id == draggedItem.id }
            dockItems.remove(draggedItem)
            dockItems.remove(targetItem)

            val folder = LauncherItem(
                type = ItemType.FOLDER,
                customTitle = "Folder"
            ).apply {
                folderItems.add(targetItem)
                folderItems.add(draggedItem)
            }
            val insertPos = slotIndex.coerceIn(0, dockItems.size)
            dockItems.add(insertPos, folder)
            cfg.setHomeItems(homeItems)
            cfg.setDockItems(dockItems)
            renderHome()
            renderDock()
            Toast.makeText(this, "Folder dibuat di dock", Toast.LENGTH_SHORT).show()
            return true
        }

        if (draggedItem in dockItems) {
            // Reorder dalam dock
            dockItems.remove(draggedItem)
            val insertPos = slotIndex.coerceIn(0, dockItems.size)
            dockItems.add(insertPos, draggedItem)
            cfg.setDockItems(dockItems)
            renderDock()
            return true
        }

        // Dari home pindah ke dock
        if (dockItems.size >= MAX_DOCK) {
            Toast.makeText(this, "Dock penuh (maks. $MAX_DOCK)", Toast.LENGTH_SHORT).show()
            return false
        }

        homeItems.removeAll { it.id == draggedItem.id }
        val insertPos = slotIndex.coerceIn(0, dockItems.size)
        dockItems.add(insertPos, draggedItem)
        cfg.setHomeItems(homeItems)
        cfg.setDockItems(dockItems)
        renderHome()
        renderDock()
        Toast.makeText(this, "Dipindahkan ke dock", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun addToDock(app: AppInfo) {
        if (dockItems.size >= MAX_DOCK) {
            Toast.makeText(this, "Dock penuh (maks. $MAX_DOCK)", Toast.LENGTH_SHORT).show()
            return
        }
        val item = LauncherItem(
            type = ItemType.APP,
            packageName = app.packageName,
            componentName = app.component.flattenToString()
        )
        dockItems.add(item)
        cfg.setDockItems(dockItems)
        renderDock()
        Toast.makeText(this, "${app.label} ditambahkan ke dock", Toast.LENGTH_SHORT).show()
    }

    private fun removeFromDock(item: LauncherItem) {
        dockItems.removeAll { it.id == item.id }
        cfg.setDockItems(dockItems)
        renderDock()
        Toast.makeText(this, "Dihapus dari dock", Toast.LENGTH_SHORT).show()
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
                packageName = pkg,
                highlight = true
            )
        )
        cfg.setHomeItems(homeItems)
        renderHome()
        homePager.setCurrentItem(targetPage, true)
        Toast.makeText(this, "Aplikasi baru ditambahkan ke halaman ${targetPage + 1}", Toast.LENGTH_SHORT).show()
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

    private fun confirmDeleteItem(item: LauncherItem) {
        if (item.type == ItemType.WIDGET) {
            removeWidget(item)
            return
        }
        val app = allApps.find { it.packageName == item.packageName }
        val title = item.customTitle ?: app?.label ?: "Item"

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(arrayOf("Hapus dari layar utama", "Copot pemasangan", "Batal")) { _, which ->
                when (which) {
                    0 -> {
                        homeItems.removeAll { it.id == item.id }
                        dockItems.removeAll { it.id == item.id }
                        cfg.setHomeItems(homeItems)
                        cfg.setDockItems(dockItems)
                        renderHome()
                        renderDock()
                    }
                    1 -> {
                        if (item.packageName.isNotEmpty()) {
                            startActivity(
                                Intent(Intent.ACTION_DELETE, Uri.fromParts("package", item.packageName, null))
                            )
                        }
                    }
                }
            }
            .show()
    }

    // ================================================================ drag and drop

    private fun startDragItem(item: LauncherItem, view: View): Boolean {
        if (cfg.lockLayout) return false
        val clip = ClipData.newPlainText("item_id", item.id)
        val shadow = View.DragShadowBuilder(view)
        view.startDragAndDrop(clip, shadow, item, 0)
        view.alpha = 0.2f
        return true
    }

    private fun handleCellDrag(page: Int, cellLayout: CellLayout, event: DragEvent): Boolean {
        val draggedItem = event.localState as? LauncherItem ?: return false

        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> return true

            DragEvent.ACTION_DRAG_LOCATION -> {
                // Auto scroll halaman di tepi layar
                val edgePad = dp(45)
                if (event.x < edgePad) {
                    if (page > 0 && edgeScrollRunnable == null) {
                        edgeScrollRunnable = Runnable {
                            homePager.currentItem = page - 1
                            edgeScrollRunnable = null
                        }
                        edgeScrollHandler.postDelayed(edgeScrollRunnable!!, 600)
                    }
                } else if (event.x > cellLayout.width - edgePad) {
                    if (page < homePagerAdapter.pageCount - 1 && edgeScrollRunnable == null) {
                        edgeScrollRunnable = Runnable {
                            homePager.currentItem = page + 1
                            edgeScrollRunnable = null
                        }
                        edgeScrollHandler.postDelayed(edgeScrollRunnable!!, 600)
                    }
                } else {
                    cancelEdgeScroll()
                }
                return true
            }

            DragEvent.ACTION_DROP -> {
                cancelEdgeScroll()
                val (targetX, targetY) = cellLayout.pointToCell(event.x, event.y)
                val targetChild = cellLayout.findChildAtCell(targetX, targetY)
                val targetItem = targetChild?.tag as? LauncherItem

                // 1. Drop ke aplikasi lain -> Buat Folder
                if (draggedItem.type == ItemType.APP &&
                    targetItem != null && targetItem.type == ItemType.APP &&
                    targetItem.id != draggedItem.id
                ) {
                    homeItems.removeAll { it.id == draggedItem.id || it.id == targetItem.id }
                    dockItems.removeAll { it.id == draggedItem.id || it.id == targetItem.id }

                    val folder = LauncherItem(
                        type = ItemType.FOLDER,
                        page = page,
                        cellX = targetX,
                        cellY = targetY,
                        customTitle = "Folder"
                    ).apply {
                        folderItems.add(targetItem)
                        folderItems.add(draggedItem)
                    }
                    homeItems.add(folder)
                    cfg.setHomeItems(homeItems)
                    cfg.setDockItems(dockItems)
                    renderHome()
                    renderDock()
                    Toast.makeText(this, "Folder dibuat", Toast.LENGTH_SHORT).show()
                    return true
                }

                // 2. Drop ke folder yang sudah ada -> Masukkan ke folder
                if (draggedItem.type == ItemType.APP &&
                    targetItem != null && targetItem.type == ItemType.FOLDER &&
                    targetItem.id != draggedItem.id
                ) {
                    homeItems.removeAll { it.id == draggedItem.id }
                    dockItems.removeAll { it.id == draggedItem.id }
                    targetItem.folderItems.add(draggedItem)
                    cfg.setHomeItems(homeItems)
                    cfg.setDockItems(dockItems)
                    renderHome()
                    renderDock()
                    Toast.makeText(this, "Ditambahkan ke folder", Toast.LENGTH_SHORT).show()
                    return true
                }

                // 3. Drop ke sel kosong atau tukar posisi
                dockItems.removeAll { it.id == draggedItem.id }
                cfg.setDockItems(dockItems)

                if (targetItem != null && targetItem.id != draggedItem.id) {
                    targetItem.page = draggedItem.page
                    targetItem.cellX = draggedItem.cellX
                    targetItem.cellY = draggedItem.cellY
                }

                draggedItem.page = page
                draggedItem.cellX = targetX
                draggedItem.cellY = targetY

                if (!homeItems.any { it.id == draggedItem.id }) {
                    homeItems.add(draggedItem)
                }

                cfg.setHomeItems(homeItems)
                renderHome()
                renderDock()
                return true
            }

            DragEvent.ACTION_DRAG_ENDED -> {
                cancelEdgeScroll()
                renderHome()
                renderDock()
                return true
            }
        }
        return false
    }

    private fun cancelEdgeScroll() {
        edgeScrollRunnable?.let { edgeScrollHandler.removeCallbacks(it) }
        edgeScrollRunnable = null
    }

    // ================================================================ dialog folder

    private fun showFolderDialog(folder: LauncherItem) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_folder, null)
        val titleEdit = dialogView.findViewById<EditText>(R.id.folderTitle)
        val folderRecycler = dialogView.findViewById<RecyclerView>(R.id.folderRecycler)

        titleEdit.setText(folder.customTitle ?: "Folder")

        var dialog: AlertDialog? = null

        val folderApps = folder.folderItems.mapNotNull { child ->
            allApps.find { it.packageName == child.packageName }?.let { app ->
                Pair(child, app)
            }
        }

        val adapter = AppAdapter(
            onClick = { app, v ->
                dialog?.dismiss()
                launch(app, v)
            },
            onLongClick = { app, _ ->
                AlertDialog.Builder(this)
                    .setTitle(app.label)
                    .setItems(arrayOf("Keluarkan dari folder", "Info aplikasi", "Copot pemasangan")) { _, which ->
                        when (which) {
                            0 -> {
                                val removedChild = folder.folderItems.find { it.packageName == app.packageName }
                                if (removedChild != null) {
                                    folder.folderItems.remove(removedChild)
                                    addToHome(app)
                                    if (folder.folderItems.size <= 1) {
                                        folder.folderItems.forEach { rem ->
                                            allApps.find { it.packageName == rem.packageName }?.let { addToHome(it) }
                                        }
                                        homeItems.removeAll { it.id == folder.id }
                                        dockItems.removeAll { it.id == folder.id }
                                    }
                                    cfg.setHomeItems(homeItems)
                                    cfg.setDockItems(dockItems)
                                    renderHome()
                                    renderDock()
                                    dialog?.dismiss()
                                }
                            }
                            1 -> {
                                startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", app.packageName, null)
                                    )
                                )
                            }
                            2 -> {
                                startActivity(
                                    Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))
                                )
                            }
                        }
                    }
                    .show()
            }
        )

        adapter.submit(folderApps.map { it.second })
        adapter.applyStyle(dp(cfg.iconSizeDp), cfg.showLabels, Color.WHITE)

        folderRecycler.layoutManager = GridLayoutManager(this, 3)
        folderRecycler.adapter = adapter

        titleEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val newTitle = s.toString().trim()
                folder.customTitle = if (newTitle.isNotEmpty()) newTitle else null
                cfg.setHomeItems(homeItems)
                cfg.setDockItems(dockItems)
                renderHome()
                renderDock()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
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
        WidgetPreviewDialog(this) { info -> addWidget(info) }.show()
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
            REQ_CUSTOM_ICON -> {
                if (resultCode == RESULT_OK && data?.data != null && pendingCustomIconPackage.isNotEmpty()) {
                    val uri = data.data!!
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: Exception) {
                        // abaikan
                    }
                    val map = cfg.customIcons
                    map[pendingCustomIconPackage] = uri.toString()
                    cfg.customIcons = map
                    pendingCustomIconPackage = ""
                    reloadApps()
                    Toast.makeText(this, "Ikon diperbarui", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun removeWidget(item: LauncherItem) {
        if (cfg.lockLayout) {
            Toast.makeText(this, "Tata letak terkunci", Toast.LENGTH_SHORT).show()
            return
        }
        val wid = item.widgetId
        if (wid != null) {
            try {
                appWidgetHost.deleteAppWidgetId(wid)
            } catch (e: Exception) {
                // abaikan
            }
            cfg.widgetIds = cfg.widgetIds - wid
        }
        homeItems.removeAll { it.id == item.id }
        cfg.setHomeItems(homeItems)
        renderHome()
        Toast.makeText(this, "Widget dihapus", Toast.LENGTH_SHORT).show()
    }

    private fun showWidgetResize(item: LauncherItem) {
        val sizes = listOf(
            1 to 1, 2 to 1, 2 to 2, 3 to 1, 3 to 2,
            4 to 1, 4 to 2, 4 to 3, 5 to 2, 6 to 2
        ).filter { it.first <= cfg.columns && it.second <= cfg.rows }
        val labels = sizes.map { "${it.first} x ${it.second}" }.toTypedArray()
        val current = sizes.indexOfFirst { it.first == item.spanX && it.second == item.spanY }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Ubah ukuran widget")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val (sx, sy) = sizes[which]
                if (isAreaFree(item.page, item.cellX, item.cellY, sx, sy, item.id)) {
                    item.spanX = sx
                    item.spanY = sy
                    cfg.setHomeItems(homeItems)
                    renderHome()
                    dialog.dismiss()
                } else {
                    Toast.makeText(this, "Tidak muat di posisi ini", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun isAreaFree(page: Int, x: Int, y: Int, spanX: Int, spanY: Int, ignoreId: String): Boolean {
        if (x < 0 || y < 0 || x + spanX > cfg.columns || y + spanY > cfg.rows) return false
        return homeItems.filter { it.page == page && it.id != ignoreId }.none { item ->
            x < item.cellX + item.spanX && x + spanX > item.cellX &&
                y < item.cellY + item.spanY && y + spanY > item.cellY
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
                removeWidget(widgetItems[which])
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ================================================================ menu

    private fun showHomeMenu() {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (!cfg.lockLayout) {
            labels.add("Ubah tata letak (Mode edit)")
            actions.add { enterEditMode() }
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
            labels.add("Ubah tata letak (Mode edit)")
            actions.add { enterEditMode() }
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
        labels.add("Ganti nama aplikasi")
        actions.add { promptRenameApp(app) }
        labels.add("Ganti ikon aplikasi")
        actions.add { pickCustomIcon(app) }
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
        labels.add("Ganti nama aplikasi")
        actions.add { promptRenameApp(app) }
        labels.add("Ganti ikon aplikasi")
        actions.add { pickCustomIcon(app) }
        labels.add("Pilih banyak")
        actions.add { enterSelection(app.packageName) }
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

    private fun promptRenameApp(app: AppInfo) {
        val input = EditText(this).apply {
            setText(app.label)
            setSingleLine()
            setSelection(app.label.length)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Ganti nama aplikasi")
            .setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                val name = input.text.toString().trim()
                val map = cfg.customLabels
                if (name.isEmpty() || name == app.label) map.remove(app.packageName)
                else map[app.packageName] = name
                cfg.customLabels = map
                reloadApps()
            }
            .setNeutralButton("Kembalikan") { _, _ ->
                val map = cfg.customLabels
                map.remove(app.packageName)
                cfg.customLabels = map
                reloadApps()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun pickCustomIcon(app: AppInfo) {
        if (cfg.customIcons.containsKey(app.packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Ikon ${app.label}")
                .setItems(arrayOf("Pilih gambar baru", "Kembalikan ikon bawaan")) { _, which ->
                    if (which == 0) openIconPicker(app)
                    else {
                        val map = cfg.customIcons
                        map.remove(app.packageName)
                        cfg.customIcons = map
                        reloadApps()
                    }
                }
                .show()
            return
        }
        openIconPicker(app)
    }

    private fun openIconPicker(app: AppInfo) {
        pendingCustomIconPackage = app.packageName
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        try {
            startActivityForResult(intent, REQ_CUSTOM_ICON)
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak bisa membuka galeri", Toast.LENGTH_SHORT).show()
        }
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
            "Tombol Pustaka di dock: ${onOff(cfg.showDrawerButton, "Tampil", "Sembunyi")}",
            "Nama aplikasi: ${onOff(cfg.showLabels, "Tampil", "Sembunyi")}",
            "Warna nama di layar utama: ${LauncherPrefs.LABEL_COLOR_NAMES[cfg.labelColorIndex]}",
            "Pengaturan widget jam",
            "Transparansi kaca: ${LauncherPrefs.GLASS_NAMES[cfg.glassIndex]}",
            "Refresh rate tertinggi: ${onOff(cfg.highRefresh, "Aktif", "Mati")}",
            "Kelola halaman (${homePagerAdapter.pageCount} halaman)",
            "Aplikasi tersembunyi (${cfg.hidden.size})",
            "Atur ulang layar utama dan dock",
            "Jadikan launcher default",
            "Pustaka aplikasi: urutan, kategori, A-Z, terakhir dipakai"
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
                    3 -> {
                        cfg.showDrawerButton = !cfg.showDrawerButton
                        renderDock()
                        showSettings()
                    }
                    4 -> chooseOne("Nama aplikasi", arrayOf("Tampil", "Sembunyi"), if (cfg.showLabels) 0 else 1) {
                        cfg.showLabels = (it == 0)
                        applyStyles()
                        showSettings()
                    }
                    5 -> chooseOne("Warna nama di layar utama", LauncherPrefs.LABEL_COLOR_NAMES, cfg.labelColorIndex) {
                        cfg.labelColorIndex = it
                        applyStyles()
                        showSettings()
                    }
                    6 -> showClockSettings()
                    7 -> chooseOne("Transparansi kaca", LauncherPrefs.GLASS_NAMES, cfg.glassIndex) {
                        cfg.glassIndex = it
                        applyStyles()
                        showSettings()
                    }
                    8 -> chooseOne("Refresh rate tertinggi", arrayOf("Aktif", "Mati"), if (cfg.highRefresh) 0 else 1) {
                        cfg.highRefresh = (it == 0)
                        applyStyles()
                        showSettings()
                    }
                    9 -> showPageManager()
                    10 -> showHiddenApps()
                    11 -> confirmReset()
                    12 -> openDefaultLauncherSettings()
                    13 -> showLibrarySettings()
                }
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun showLibrarySettings() {
        val items = arrayOf(
            "Urutkan: ${LauncherPrefs.SORT_NAMES[cfg.librarySort]}",
            "Kategori otomatis: ${onOff(cfg.showCategories, "Tampil", "Sembunyi")}",
            "Indeks A-Z: ${onOff(cfg.showLetterIndex, "Tampil", "Sembunyi")}",
            "Baris terakhir dipakai: ${onOff(cfg.showRecentRow, "Tampil", "Sembunyi")}",
            "Baris paling sering: ${onOff(cfg.showFrequentRow, "Tampil", "Sembunyi")}",
            "Aplikasi tersembunyi (${cfg.hidden.size})",
            "Izin Usage Access: ${onOff(UsageStatsHelper.hasAccess(this), "Diberikan", "Belum")}",
            "Reset nama & ikon khusus (${cfg.customLabels.size + cfg.customIcons.size})"
        )
        AlertDialog.Builder(this)
            .setTitle("Pustaka Aplikasi")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> chooseOne("Urutkan aplikasi", LauncherPrefs.SORT_NAMES, cfg.librarySort) {
                        cfg.librarySort = it
                        refreshLibrary()
                        showLibrarySettings()
                    }
                    1 -> {
                        cfg.showCategories = !cfg.showCategories
                        buildCategoryChips()
                        refreshLibrary()
                        showLibrarySettings()
                    }
                    2 -> {
                        cfg.showLetterIndex = !cfg.showLetterIndex
                        updateLetterIndexVisibility()
                        showLibrarySettings()
                    }
                    3 -> {
                        cfg.showRecentRow = !cfg.showRecentRow
                        refreshLibrary()
                        showLibrarySettings()
                    }
                    4 -> {
                        cfg.showFrequentRow = !cfg.showFrequentRow
                        refreshLibrary()
                        showLibrarySettings()
                    }
                    5 -> showHiddenApps()
                    6 -> UsageStatsHelper.openAccessSettings(this)
                    7 -> {
                        cfg.customLabels = mutableMapOf()
                        cfg.customIcons = mutableMapOf()
                        reloadApps()
                        showLibrarySettings()
                    }
                }
            }
            .setNegativeButton("Kembali") { _, _ -> showSettings() }
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
                dockItems.clear()
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

    /**
     * Mengembalikan daftar nama paket yang terpasang dan dapat menangani daftar intent yang diberikan.
     * Dipakai untuk menentukan dock dan layar utama bawaan.
     */
    private fun resolvePackages(intents: List<Intent>): List<String> =
        intents.mapNotNull { intent ->
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.distinct()
}
