package com.gtlauncher.app

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private val onClick: (AppInfo, View) -> Unit,
    private val onLongClick: (AppInfo, View) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    private var items: List<AppInfo> = emptyList()
    private var iconPx = 0
    private var showLabels = true
    private var labelColor = Color.WHITE
    private var labelSizeSp = 11f
    private var typeface: Typeface? = null

    var badges: (String) -> Int = { 0 }
    var badgeStyle = 1
    var selectionMode = false
    var selected: Set<String> = emptySet()

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
    fun applyStyle(iconPx: Int, showLabels: Boolean, labelColor: Int, labelSizeSp: Float = 11f) {
        this.iconPx = iconPx
        this.showLabels = showLabels
        this.labelColor = labelColor
        this.labelSizeSp = labelSizeSp
        notifyDataSetChanged()
    }

    fun setTypeface(tf: Typeface?) {
        typeface = tf
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val label: TextView = view.findViewById(R.id.appLabel)
        val badge: TextView = view.findViewById(R.id.appBadge)
        val check: TextView = view.findViewById(R.id.appCheck)
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
        holder.icon.setImageDrawable(app.icon)

        holder.label.text = app.label
        holder.label.visibility = if (showLabels) View.VISIBLE else View.GONE
        holder.label.setTextColor(labelColor)
        holder.label.textSize = labelSizeSp
        if (typeface != null) holder.label.typeface = typeface
        holder.label.setShadowLayer(
            if (labelColor == Color.WHITE) 4f else 0f,
            0f,
            0f,
            0x80000000.toInt()
        )

        val count = if (badgeStyle == 2) 0 else badges(app.packageName)
        if (count > 0 && !selectionMode) {
            holder.badge.visibility = View.VISIBLE
            if (badgeStyle == 0) {
                holder.badge.text = "●"
                holder.badge.textSize = 8f
            } else {
                holder.badge.text = if (count > 99) "99+" else count.toString()
                holder.badge.textSize = 10f
            }
        } else {
            holder.badge.visibility = View.GONE
        }

        if (selectionMode) {
            holder.check.visibility = View.VISIBLE
            val isSelected = app.packageName in selected
            holder.check.alpha = if (isSelected) 1f else 0.3f
            holder.check.text = if (isSelected) "✓" else ""
            holder.itemView.setBackgroundResource(
                if (isSelected) R.drawable.bg_selected else 0
            )
        } else {
            holder.check.visibility = View.GONE
            holder.itemView.setBackgroundResource(0)
        }

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
