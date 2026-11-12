package com.gtlauncher.app

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * Custom ViewGroup yang menata item (aplikasi, folder, widget)
 * dalam matriks sel grid (kolom x baris).
 */
class CellLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    var columns: Int = 4
        set(value) {
            field = value.coerceAtLeast(1)
            requestLayout()
        }

    var rows: Int = 5
        set(value) {
            field = value.coerceAtLeast(1)
            requestLayout()
        }

    class LayoutParams : ViewGroup.LayoutParams {
        var cellX: Int = 0
        var cellY: Int = 0
        var spanX: Int = 1
        var spanY: Int = 1

        constructor(cellX: Int, cellY: Int, spanX: Int = 1, spanY: Int = 1) : super(WRAP_CONTENT, WRAP_CONTENT) {
            this.cellX = cellX
            this.cellY = cellY
            this.spanX = spanX
            this.spanY = spanY
        }

        constructor(c: Context, attrs: AttributeSet?) : super(c, attrs)
        constructor(source: ViewGroup.LayoutParams?) : super(source) {
            if (source is LayoutParams) {
                this.cellX = source.cellX
                this.cellY = source.cellY
                this.spanX = source.spanX
                this.spanY = source.spanY
            }
        }
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is LayoutParams

    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = LayoutParams(0, 0, 1, 1)

    override fun generateLayoutParams(attrs: AttributeSet?): ViewGroup.LayoutParams = LayoutParams(context, attrs)

    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams = LayoutParams(p)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        val availW = width - paddingLeft - paddingRight
        val availH = height - paddingTop - paddingBottom

        val cellW = if (columns > 0) availW / columns else 0
        val cellH = if (rows > 0) availH / rows else 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue

            val lp = child.layoutParams as? LayoutParams ?: LayoutParams(0, 0, 1, 1).also {
                child.layoutParams = it
            }

            val childW = (lp.spanX * cellW).coerceAtLeast(0)
            val childH = (lp.spanY * cellH).coerceAtLeast(0)

            val wSpec = MeasureSpec.makeMeasureSpec(childW, MeasureSpec.EXACTLY)
            val hSpec = MeasureSpec.makeMeasureSpec(childH, MeasureSpec.EXACTLY)
            child.measure(wSpec, hSpec)
        }

        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val availW = (r - l) - paddingLeft - paddingRight
        val availH = (b - t) - paddingTop - paddingBottom

        val cellW = if (columns > 0) availW / columns else 0
        val cellH = if (rows > 0) availH / rows else 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue

            val lp = child.layoutParams as? LayoutParams ?: continue

            val cl = paddingLeft + lp.cellX * cellW
            val ct = paddingTop + lp.cellY * cellH
            val cr = cl + lp.spanX * cellW
            val cb = ct + lp.spanY * cellH

            child.layout(cl, ct, cr, cb)
        }
    }

    /**
     * Memeriksa apakah sel (x, y) sampai (x + spanX - 1, y + spanY - 1) sudah terisi.
     */
    fun isRegionOccupied(targetX: Int, targetY: Int, spanX: Int, spanY: Int, ignoreView: View? = null): Boolean {
        if (targetX < 0 || targetY < 0 || targetX + spanX > columns || targetY + spanY > rows) {
            return true
        }

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === ignoreView || child.visibility == View.GONE) continue
            val lp = child.layoutParams as? LayoutParams ?: continue

            val overlap = !(targetX + spanX <= lp.cellX ||
                targetX >= lp.cellX + lp.spanX ||
                targetY + spanY <= lp.cellY ||
                targetY >= lp.cellY + lp.spanY)

            if (overlap) return true
        }
        return false
    }

    /**
     * Mencari sel kosong pertama yang muat ukuran spanX x spanY.
     */
    fun findFirstVacantCell(spanX: Int = 1, spanY: Int = 1): Pair<Int, Int>? {
        for (y in 0..(rows - spanY)) {
            for (x in 0..(columns - spanX)) {
                if (!isRegionOccupied(x, y, spanX, spanY)) {
                    return Pair(x, y)
                }
            }
        }
        return null
    }

    /**
     * Mengonversi koordinat pixel (x, y) menjadi koordinat sel (cellX, cellY).
     */
    fun pointToCell(px: Float, py: Float): Pair<Int, Int> {
        val availW = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val availH = (height - paddingTop - paddingBottom).coerceAtLeast(1)
        val cellW = if (columns > 0) availW / columns else 1
        val cellH = if (rows > 0) availH / rows else 1

        val cx = ((px - paddingLeft) / cellW).toInt().coerceIn(0, columns - 1)
        val cy = ((py - paddingTop) / cellH).toInt().coerceIn(0, rows - 1)
        return Pair(cx, cy)
    }

    /**
     * Mencari child view yang menempati sel (cellX, cellY).
     */
    fun findChildAtCell(cellX: Int, cellY: Int, ignoreView: View? = null): View? {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === ignoreView || child.visibility == View.GONE) continue
            val lp = child.layoutParams as? LayoutParams ?: continue
            if (cellX >= lp.cellX && cellX < lp.cellX + lp.spanX &&
                cellY >= lp.cellY && cellY < lp.cellY + lp.spanY) {
                return child
            }
        }
        return null
    }
}
