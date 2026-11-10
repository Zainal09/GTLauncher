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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var root: View
    private lateinit var homeLayer: View
    private lateinit var drawerLayer: View
    private lateinit var searchInput: EditText
    private lateinit var recycler: RecyclerView
    private lateinit var dock: LinearLayout
    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: AppAdapter

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
        dock = findViewById(R.id.dock)

        // Beri ruang untuk status bar, navigation bar, dan keyboard
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            homeLayer.setPadding(dp(24) + bars.left, bars.top, dp(24) + bars.right, bars.bottom)
            drawerLayer.setPadding(dp(16) + bars.left, bars.top, dp(16) + bars.right, bars.bottom)
            insets
        }

        adapter = AppAdapter(
            onClick = { app -> launch(app) },
            onLongClick = { app -> showAppMenu(app) }
        )
        recycler.layoutManager = GridLayoutManager(this, 5)
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)

        setupHomeGestures()
        setupDrawerGestures()
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
        // Launcher tidak boleh menutup dirinya sendiri; cukup tutup app drawer
        if (drawerOpen) closeDrawer()
    }

    // ---------------------------------------------------------------- gestures

    private fun setupHomeGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (velocityY < -800f) {
                    openDrawer()
                    return true
                }
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                try {
                    startActivity(
                        Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Ganti wallpaper")
                    )
                } catch (ex: Exception) {
                    Toast.makeText(this@MainActivity, "Tidak bisa membuka pengaturan wallpaper", Toast.LENGTH_SHORT).show()
                }
            }
        })
        homeLayer.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }
    }

    private fun setupDrawerGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                // Geser cepat ke bawah saat daftar sudah di paling atas -> tutup drawer
                if (velocityY > 2500f && !recycler.canScrollVertically(-1)) {
                    closeDrawer()
                    return true
                }
                return false
            }
        })
        recycler.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            false
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
            filtered.firstOrNull()?.let { launch(it) }
            true
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
                        icon = it.loadIcon(pm)
                    )
                }
                .sortedBy { it.label.lowercase() }
            ui.post {
                allApps = list
                applyFilter()
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
        adapter.submit(filtered)
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

    private fun showAppMenu(app: AppInfo) {
        val items = arrayOf("Tambah ke dock", "Info aplikasi", "Copot pemasangan")
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(items) { _, which ->
                try {
                    when (which) {
                        0 -> addToDock(app)
                        1 -> startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", app.packageName, null)
                            )
                        )
                        2 -> startActivity(
                            Intent(
                                Intent.ACTION_DELETE,
                                Uri.fromParts("package", app.packageName, null)
                            )
                        )
                        else -> {}
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Aksi tidak tersedia", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    // ---------------------------------------------------------------- drawer

    private fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true
        drawerLayer.visibility = View.VISIBLE
        drawerLayer.alpha = 0f
        drawerLayer.translationY = dp(80).toFloat()
        drawerLayer.animate().alpha(1f).translationY(0f).setDuration(220).start()
        homeLayer.animate().alpha(0f).setDuration(180).start()
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
                .translationY(dp(80).toFloat())
                .setDuration(180)
                .withEndAction {
                    if (!drawerOpen) drawerLayer.visibility = View.GONE
                }
                .start()
            homeLayer.animate().alpha(1f).setDuration(220).start()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    // ---------------------------------------------------------------- dock

    private fun defaultDock(): List<String> {
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        )
        return intents.mapNotNull {
            packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.distinct()
    }

    private fun dockPackages(): List<String> {
        val saved = prefs.getString("dock", null)
        val pkgs = if (saved != null) {
            saved.split(",").filter { it.isNotEmpty() }
        } else {
            defaultDock()
        }
        // Hanya yang benar-benar terpasang dan punya ikon launcher
        return pkgs.filter { p -> allApps.any { it.packageName == p } }.take(5)
    }

    private fun saveDock(list: List<String>) {
        prefs.edit().putString("dock", list.joinToString(",")).apply()
    }

    private fun renderDock() {
        dock.removeAllViews()
        val size = dp(52)
        val gap = dp(10)
        dockPackages()
            .mapNotNull { pkg -> allApps.find { it.packageName == pkg } }
            .forEach { app ->
                val iv = ImageView(this)
                // Pakai salinan ikon supaya tidak bentrok dengan ikon di app drawer
                val icon = app.icon.constantState?.newDrawable()?.mutate() ?: app.icon
                iv.setImageDrawable(icon)
                val lp = LinearLayout.LayoutParams(size, size)
                lp.setMargins(gap, 0, gap, 0)
                iv.layoutParams = lp
                iv.setOnClickListener { launch(app) }
                iv.setOnLongClickListener {
                    removeFromDock(app)
                    true
                }
                dock.addView(iv)
            }
    }

    private fun addToDock(app: AppInfo) {
        val current = dockPackages().toMutableList()
        if (app.packageName in current) {
            Toast.makeText(this, "Sudah ada di dock", Toast.LENGTH_SHORT).show()
            return
        }
        if (current.size >= 5) {
            Toast.makeText(
                this,
                "Dock penuh (maks. 5). Tahan ikon di dock untuk menghapus.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        current.add(app.packageName)
        saveDock(current)
        renderDock()
        Toast.makeText(this, "${app.label} ditambahkan ke dock", Toast.LENGTH_SHORT).show()
    }

    private fun removeFromDock(app: AppInfo) {
        val current = dockPackages().toMutableList()
        current.remove(app.packageName)
        saveDock(current)
        renderDock()
        Toast.makeText(this, "${app.label} dihapus dari dock", Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- util

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
