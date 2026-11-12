package com.gtlauncher.app

import android.app.Activity
import android.app.Dialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Pemilih widget Android dengan pratinjau gambar. */
class WidgetPreviewDialog(
    private val activity: Activity,
    private val onPick: (AppWidgetProviderInfo) -> Unit
) {

    fun show() {
        val providers = try {
            AppWidgetManager.getInstance(activity).installedProviders
        } catch (e: Exception) {
            emptyList<AppWidgetProviderInfo>()
        }
        if (providers.isEmpty()) {
            WKToast.show(activity, "Tidak ada widget tersedia")
            return
        }

        val dialog = Dialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_cc_panel)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(activity).apply {
                text = "Tambah widget"
                setTextColor(Color.WHITE)
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(TextView(activity).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(dp(10), dp(4), dp(4), dp(4))
            setOnClickListener { dialog.dismiss() }
        })
        root.addView(header)

        val scroll = ScrollView(activity)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val sorted = providers.sortedBy {
            try {
                it.loadLabel(activity.packageManager).toString().lowercase()
            } catch (e: Exception) {
                ""
            }
        }
        for (rowItems in sorted.chunked(2)) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(3), 0, dp(3))
            }
            for (provider in rowItems) {
                val card = card(provider) {
                    dialog.dismiss()
                    onPick(provider)
                }
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginEnd = dp(8)
                row.addView(card, lp)
            }
            content.addView(row)
        }

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnShowListener {
            val metrics = activity.resources.displayMetrics
            dialog.window?.setLayout(
                (metrics.widthPixels * 0.94f).toInt(),
                (metrics.heightPixels * 0.82f).toInt()
            )
        }
        dialog.show()
    }

    private fun card(provider: AppWidgetProviderInfo, onClick: () -> Unit): LinearLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_cc_tile)
            setPadding(dp(8), dp(8), dp(8), dp(10))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val image = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(92))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageDrawable(preview(provider))
            setBackgroundColor(0x22000000)
        }
        card.addView(image)

        val title = try {
            provider.loadLabel(activity.packageManager).toString()
        } catch (e: Exception) {
            "Widget"
        }
        val appLabel = try {
            activity.packageManager.getApplicationInfo(provider.provider.packageName, 0)
                .loadLabel(activity.packageManager).toString()
        } catch (e: Exception) {
            provider.provider.packageName
        }
        card.addView(TextView(activity).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        })
        card.addView(TextView(activity).apply {
            text = appLabel
            setTextColor(0x99FFFFFF.toInt())
            textSize = 9.5f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        return card
    }

    private fun preview(provider: AppWidgetProviderInfo): Drawable? {
        return try {
            provider.loadPreviewImage(activity, 0)
                ?: provider.loadIcon(activity, 0)
                ?: activity.packageManager.getApplicationIcon(provider.provider.packageName)
        } catch (e: Exception) {
            null
        }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}

/** Toast kecil tanpa ketergantungan lain. */
object WKToast {
    fun show(activity: Activity, text: String) {
        android.widget.Toast.makeText(activity, text, android.widget.Toast.LENGTH_SHORT).show()
    }
}
