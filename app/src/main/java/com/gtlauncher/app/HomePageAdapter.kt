package com.gtlauncher.app

import android.graphics.Color
import android.view.DragEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter untuk ViewPager2 layar utama yang mengatur halaman CellLayout.
 * Mendukung mode edit bergoyang ala iOS, pembuatan folder, dan drag-and-drop.
 */
class HomePageAdapter(
    private val getColumns: () -> Int,
    private val getRows: () -> Int,
    private val getItemsForPage: (page: Int) -> List<LauncherItem>,
    private val getAppInfo: (item: LauncherItem) -> AppInfo?,
    private val onItemClick: (LauncherItem, AppInfo?, View) -> Unit,
    private val onItemLongClick: (LauncherItem, AppInfo?, View) -> Unit,
    private val onPageLongClick: (page: Int) -> Unit,
    private val onDeleteClick: (LauncherItem) -> Unit,
    private val onFolderClick: (LauncherItem, View) -> Unit,
    private val onItemStartDrag: (LauncherItem, View) -> Boolean,
    private val onCellDrag: (page: Int, cellLayout: CellLayout, event: DragEvent) -> Boolean,
    private val onWidgetCreateView: (widgetId: Int) -> View?,
    private val getIconPx: () -> Int,
    private val getShowLabels: () -> Boolean,
    private val getLabelColor: () -> Int
) : RecyclerView.Adapter<HomePageAdapter.PageVH>() {

    var pageCount: Int = 1
        set(value) {
            field = value.coerceAtLeast(1)
            notifyDataSetChanged()
        }

    var isEditMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    class PageVH(val cellLayout: CellLayout) : RecyclerView.ViewHolder(cellLayout)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
        val cellLayout = CellLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        return PageVH(cellLayout)
    }

    override fun getItemCount(): Int = pageCount

    override fun onBindViewHolder(holder: PageVH, position: Int) {
        val cellLayout = holder.cellLayout
        val cols = getColumns()
        val rows = getRows()
        cellLayout.columns = cols
        cellLayout.rows = rows
        cellLayout.removeAllViews()

        cellLayout.setOnLongClickListener {
            onPageLongClick(position)
            true
        }

        cellLayout.setOnDragListener { _, event ->
            onCellDrag(position, cellLayout, event)
        }

        val items = getItemsForPage(position)
        val iconPx = getIconPx()
        val showLabels = getShowLabels()
        val labelColor = getLabelColor()

        for (item in items) {
            when (item.type) {
                ItemType.APP -> {
                    val app = getAppInfo(item) ?: continue
                    val itemView = LayoutInflater.from(cellLayout.context)
                        .inflate(R.layout.item_app, cellLayout, false)

                    val iconView = itemView.findViewById<ImageView>(R.id.appIcon)
                    val labelView = itemView.findViewById<TextView>(R.id.appLabel)
                    val badgeDelete = itemView.findViewById<ImageView>(R.id.badgeDelete)

                    if (iconPx > 0) {
                        val lp = iconView.layoutParams
                        if (lp.width != iconPx) {
                            lp.width = iconPx
                            lp.height = iconPx
                            iconView.layoutParams = lp
                        }
                    }
                    iconView.setImageDrawable(app.icon)

                    labelView.text = item.customTitle ?: app.label
                    labelView.visibility = if (showLabels) View.VISIBLE else View.GONE
                    labelView.setTextColor(labelColor)
                    labelView.setShadowLayer(
                        if (labelColor == Color.WHITE) 4f else 0f,
                        0f, 0f, 0x80000000.toInt()
                    )

                    if (isEditMode) {
                        badgeDelete.visibility = View.VISIBLE
                        badgeDelete.setOnClickListener { onDeleteClick(item) }
                        itemView.startJiggle(position * 7 + item.cellX * 3 + item.cellY)
                        itemView.setOnClickListener { /* Di mode edit, klik biasa diabaikan */ }
                        itemView.setOnLongClickListener {
                            onItemStartDrag(item, itemView)
                        }
                    } else {
                        badgeDelete.visibility = View.GONE
                        itemView.stopJiggle()
                        itemView.pressEffect()
                        itemView.setOnClickListener { onItemClick(item, app, itemView) }
                        itemView.setOnLongClickListener {
                            onItemLongClick(item, app, itemView)
                            true
                        }
                    }

                    // Simpan item di tag untuk mempermudah deteksi target saat drag
                    itemView.tag = item

                    val lp = CellLayout.LayoutParams(item.cellX, item.cellY, item.spanX, item.spanY)
                    cellLayout.addView(itemView, lp)
                }

                ItemType.FOLDER -> {
                    val itemView = LayoutInflater.from(cellLayout.context)
                        .inflate(R.layout.item_app, cellLayout, false)

                    val iconView = itemView.findViewById<ImageView>(R.id.appIcon)
                    val labelView = itemView.findViewById<TextView>(R.id.appLabel)
                    val badgeDelete = itemView.findViewById<ImageView>(R.id.badgeDelete)

                    if (iconPx > 0) {
                        val lp = iconView.layoutParams
                        if (lp.width != iconPx) {
                            lp.width = iconPx
                            lp.height = iconPx
                            iconView.layoutParams = lp
                        }
                    }

                    // Gambar pratinjau mini 3x3 folder kaca
                    val childIcons = item.folderItems.mapNotNull { getAppInfo(it)?.icon }
                    val folderIcon = createFolderIcon(cellLayout.context, childIcons, iconPx)
                    iconView.setImageDrawable(folderIcon)

                    labelView.text = item.customTitle ?: "Folder"
                    labelView.visibility = if (showLabels) View.VISIBLE else View.GONE
                    labelView.setTextColor(labelColor)
                    labelView.setShadowLayer(
                        if (labelColor == Color.WHITE) 4f else 0f,
                        0f, 0f, 0x80000000.toInt()
                    )

                    if (isEditMode) {
                        badgeDelete.visibility = View.VISIBLE
                        badgeDelete.setOnClickListener { onDeleteClick(item) }
                        itemView.startJiggle(position * 7 + item.cellX * 3 + item.cellY)
                        itemView.setOnClickListener { /* Di mode edit, klik biasa diabaikan */ }
                        itemView.setOnLongClickListener {
                            onItemStartDrag(item, itemView)
                        }
                    } else {
                        badgeDelete.visibility = View.GONE
                        itemView.stopJiggle()
                        itemView.pressEffect()
                        itemView.setOnClickListener { onFolderClick(item, itemView) }
                        itemView.setOnLongClickListener {
                            onItemLongClick(item, null, itemView)
                            true
                        }
                    }

                    itemView.tag = item

                    val lp = CellLayout.LayoutParams(item.cellX, item.cellY, item.spanX, item.spanY)
                    cellLayout.addView(itemView, lp)
                }

                ItemType.WIDGET -> {
                    val wid = item.widgetId ?: continue
                    val wView = onWidgetCreateView(wid) ?: continue
                    (wView.parent as? ViewGroup)?.removeView(wView)

                    if (isEditMode) {
                        wView.startJiggle(position * 5 + item.cellX)
                        wView.setOnLongClickListener {
                            onItemStartDrag(item, wView)
                        }
                    } else {
                        wView.stopJiggle()
                    }

                    wView.tag = item
                    val lp = CellLayout.LayoutParams(item.cellX, item.cellY, item.spanX, item.spanY)
                    cellLayout.addView(wView, lp)
                }
            }
        }
    }
}
