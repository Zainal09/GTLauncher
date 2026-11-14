package com.gtlauncher.app

import android.content.res.Resources
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Daftar aplikasi berbentuk grid. Dipakai di Pustaka Aplikasi dan di dalam folder.
 * Klik dan tahan mengirim aplikasi beserta tampilan yang disentuh.
 */
class AppAdapter(
    private val resources: Resources,
    private val onClick: (AppInfo, View) -> Unit,
    private val onLongClick: (AppInfo, View) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    private var items: List<AppInfo> = emptyList()
    private var iconPx = 0
    private var showLabels = true
    private var labelColor = Color.WHITE

    init {
        // ID stabil -> RecyclerView bisa memakai ulang tampilan dengan efisien saat scroll/filter
        setHasStableIds(true)
    }

    @Suppress("NotifyDataSetChanged")
    fun submit(list: List<AppInfo>) {
        items = list
        notifyDataSetChanged()
    }

    @Suppress("NotifyDataSetChanged")
    fun applyStyle(iconPx: Int, showLabels: Boolean, labelColor: Int) {
        this.iconPx = iconPx
        this.showLabels = showLabels
        this.labelColor = labelColor
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val label: TextView = view.findViewById(R.id.appLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        view.pressEffect()
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = items[position]

        if (iconPx > 0) {
            val lp = holder.icon.layoutParams
            if (lp.width != iconPx) {
                lp.width = iconPx
                lp.height = iconPx
                holder.icon.layoutParams = lp
            }
        }
        // Salinan ikon khusus tampilan ini (lihat AppInfo.newIcon)
        holder.icon.setImageDrawable(app.newIcon(resources))

        holder.label.text = app.label
        holder.label.visibility = if (showLabels) View.VISIBLE else View.GONE
        holder.label.setTextColor(labelColor)
        holder.label.setShadowLayer(
            if (labelColor == Color.WHITE) 4f else 0f,
            0f,
            0f,
            0x80000000.toInt()
        )

        holder.itemView.setOnClickListener { onClick(app, holder.itemView) }
        holder.itemView.setOnLongClickListener {
            onLongClick(app, holder.itemView)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long =
        items[position].component.flattenToString().hashCode().toLong()
}
