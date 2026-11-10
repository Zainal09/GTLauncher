package com.gtlauncher.app

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/** Grid yang tidak bisa di-scroll (untuk layar utama). */
private class FixedGridLayoutManager(context: Context, span: Int) : GridLayoutManager(context, span) {
    override fun canScrollVertically(): Boolean = false
}

class MainActivity : Activity() {

    private companion object {
        const val COLUMNS = 4
        const val MAX_DOCK = 4
        const val MAX_HOME = 12
    }

    private lateinit var root: View
    private lateinit var homeLayer: View
    private lateinit var drawerLayer: View
    private lateinit var searchInput: EditText
    private lateinit var recycler: RecyclerView
    private lateinit var homeRecycler: RecyclerView
    private lateinit var dock: LinearLayout
    private lateinit var prefs: SharedPreferences
    private lateinit var drawerAdapter: AppAdapter
    private lateinit var homeAdapter: AppAdapter
    private lateinit var flingDetector: GestureDetector

    private val executor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var allApps: List<AppInfo> = emptyList()
    private var filtered: List<AppInfo> = emptyList()
    private var drawerOpen = false
    private var receiverRegistered = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadApps()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("launcher", MODE_PRIVATE)

        root = findViewById(R.id.root)
        homeLayer = findViewById(R.id.homeLayer)
        drawerLayer = findViewById(R.id.drawerLayer)
        searchInput = findViewById(R.id.searchInput)
        recycler = findViewById(R.id.recycler)
        homeRecycler = findViewById(R.id.homeRecycler)
        dock = findViewById(R.id.dock)

        // Beri ruang untuk status bar, navigation bar, dan keyboard
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            homeLayer.setPadding(dp(16) + bars.left, bars.top, dp(16) + bars.right, bars.bottom)
            drawerLayer.setPadding(dp(20) + bars.left, bars.top, dp(20) + bars.right, bars.bottom)
            insets
        }

        drawerAdapter = AppAdapter(
            onClick = { app -> launch(app) },
            onLongClick = { app -> showAppMenu(app, fromHome = false) }
        )
        recycler.layoutManager = GridLayoutManager(this, COLUMNS)
        recycler.adapter = drawerAdapter
        recycler.setHasFixedSize(true)

        homeAdapter = AppAdapter(
            onClick = { app -> launch(app) },
            onLongClick = { app -> showAppMenu(app, fromHome = true) }
        )
        homeRecycler.layoutManager = FixedGridLayoutManager(this, COLUMNS)
        homeRecycler.adapter = homeAdapter
        homeRecycler.overScrollMode = View.OVER_SCROLL_NEVER

        // Tahan area kosong di layar utama -> ganti wallpaper
        homeLayer.setOnLongClickListener {
            openWallpaperPicker()
            true
        }

        setupGestures()
        setupSearch()
    }

    override fun onStart() {
        super.onStart()
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
        loadApps()
    }

    override fun onStop() {
        super.onStop()
        if (receiverRegistered) {
            try {
                unregisterReceiver(packageReceiver)
            } catch (e: IllegalArgumentException) {
                // sudah tidak terdaftar, abaikan
            }
            receiverRegistered = false
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Tombol Home ditekan saat launcher sudah terbuka -> kembali ke layar utama
        if (intent != null &&
            intent.action == Intent.ACTION_MAIN &&
            intent.hasCategory(Intent.CATEGORY_HOME)
        ) {
            closeDrawer()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Launcher tidak boleh menutup dirinya sendiri; cukup tutup Pustaka Aplikasi
        if (drawerOpen) closeDrawer()
    }

    // ---------------------------------------------------------------- gestures

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
                // Geser ke atas di layar utama -> buka Pustaka Aplikasi
                if (!drawerOpen && velocityY < -1200f && dy < -dp(80)) {
                    openDrawer()
                    return true
                }
                // Geser cepat ke bawah saat daftar sudah di paling atas -> tutup
                if (drawerOpen && velocityY > 2000f && dy > dp(80) && !recycler.canScrollVertically(-1)) {
                    closeDrawer()
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
            filtered.firstOrNull()?.let { launch(it) }
            true
        }
    }

    private fun openWallpaperPicker() {
        try {
            startActivity(
                Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Ganti wallpaper")
            )
        } catch (ex: Exception) {
            Toast.makeText(this, "Tidak bisa membuka pengaturan wallpaper", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- data

    private fun loadApps() {
        executor.execute {
            val pm = packageManager
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(query, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map {
                    AppInfo(
                        label = it.loadLabel(pm).toString(),
                        packageName = it.activityInfo.packageName,
                        component = ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                        icon = it.loadIcon(pm).toIosBitmapDrawable(this)
                    )
                }
                .sortedBy { it.label.lowercase() }
            ui.post {
                allApps = list
                applyFilter()
                renderHome()
                renderDock()
            }
        }
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

    // ---------------------------------------------------------------- actions

    private fun launch(app: AppInfo) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak bisa membuka ${app.label}", Toast.LENGTH_SHORT).show()
        }
        if (drawerOpen) closeDrawer(immediate = true)
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

    // ---------------------------------------------------------------- Pustaka Aplikasi (drawer)

    private fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true
        setBlur(true)
        drawerLayer.visibility = View.VISIBLE
        drawerLayer.alpha = 0f
        drawerLayer.translationY = dp(60).toFloat()
        drawerLayer.animate().alpha(1f).translationY(0f).setDuration(240).start()
        homeLayer.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(200).start()
    }

    private fun closeDrawer(immediate: Boolean = false) {
        if (!drawerOpen) return
        drawerOpen = false
        setBlur(false)
        hideKeyboard()
        searchInput.setText("")
        searchInput.clearFocus()
        recycler.scrollToPosition(0)

        if (immediate) {
            drawerLayer.animate().cancel()
            drawerLayer.visibility = View.GONE
            homeLayer.animate().cancel()
            homeLayer.alpha = 1f
            homeLayer.scaleX = 1f
            homeLayer.scaleY = 1f
        } else {
            drawerLayer.animate()
                .alpha(0f)
                .translationY(dp(60).toFloat())
                .setDuration(200)
                .withEndAction {
                    if (!drawerOpen) drawerLayer.visibility = View.GONE
                }
                .start()
            homeLayer.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).start()
        }
    }

    /** Blur latar belakang (Android 12+). Kalau perangkat tidak mendukung, diabaikan. */
    private fun setBlur(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < 31) return
        try {
            val lp = window.attributes
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                lp.blurBehindRadius = 70
            } else {
                lp.blurBehindRadius = 0
                window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            }
            window.attributes = lp
        } catch (e: Exception) {
            // tidak didukung, abaikan
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    // ---------------------------------------------------------------- penyimpanan daftar

    private fun loadList(key: String): List<String>? =
        prefs.getString(key, null)?.split(",")?.filter { it.isNotEmpty() }

    private fun saveList(key: String, list: List<String>) {
        prefs.edit().putString(key, list.joinToString(",")).apply()
    }

    private fun resolvePackages(intents: List<Intent>): List<String> =
        intents.mapNotNull {
            packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.distinct()

    // ---------------------------------------------------------------- dock

    private fun defaultDock(): List<String> = resolvePackages(
        listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        )
    )

    private fun dockPackages(): List<String> {
        val pkgs = loadList("dock") ?: defaultDock()
        // Hanya yang benar-benar terpasang dan punya ikon launcher
        return pkgs.filter { p -> allApps.any { it.packageName == p } }.take(MAX_DOCK)
    }

    private fun renderDock() {
        dock.removeAllViews()
        val size = dp(60)
        dockPackages()
            .mapNotNull { pkg -> allApps.find { it.packageName == pkg } }
            .forEach { app ->
                val cell = FrameLayout(this)
                cell.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

                val iv = ImageView(this)
                iv.setImageDrawable(app.icon.constantState?.newDrawable(resources) ?: app.icon)
                iv.layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
                iv.applyIosCorners()
                iv.pressEffect()
                iv.setOnClickListener { launch(app) }
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
        saveList("dock", current)
        renderDock()
        Toast.makeText(this, "${app.label} ditambahkan ke dock", Toast.LENGTH_SHORT).show()
    }

    private fun removeFromDock(app: AppInfo) {
        val current = dockPackages().toMutableList()
        current.remove(app.packageName)
        saveList("dock", current)
        renderDock()
        Toast.makeText(this, "${app.label} dihapus dari dock", Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- layar utama

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
        val pkgs = loadList("home") ?: defaultHome()
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
        saveList("home", current)
        renderHome()
        Toast.makeText(this, "${app.label} ditambahkan ke layar utama", Toast.LENGTH_SHORT).show()
    }

    private fun removeFromHome(app: AppInfo) {
        val current = homePackages().toMutableList()
        current.remove(app.packageName)
        saveList("home", current)
        renderHome()
    }

    // ---------------------------------------------------------------- util

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
