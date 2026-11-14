package com.gtlauncher.app

import org.json.JSONArray
import org.json.JSONObject

/** Simpan dan baca tata letak layar utama sebagai JSON di SharedPreferences. */
object HomeStore {

    fun toJson(l: HomeLayout): String {
        val root = JSONObject()
        root.put("pages", l.pageCount)
        val items = JSONArray()
        for (o in l.items) items.put(itemToJson(o))
        root.put("items", items)
        val dock = JSONArray()
        for (o in l.dock) dock.put(itemToJson(o))
        root.put("dock", dock)
        return root.toString()
    }

    /** Baca JSON ke [l]. False kalau datanya rusak (isi [l] tidak dipakai). */
    fun fromJson(s: String, l: HomeLayout): Boolean {
        return try {
            val root = JSONObject(s)
            val items = root.optJSONArray("items") ?: JSONArray()
            val dock = root.optJSONArray("dock") ?: JSONArray()
            l.items.clear()
            l.dock.clear()
            var maxPage = 0
            for (i in 0 until items.length()) {
                val o = itemFromJson(items.getJSONObject(i))
                if (o.page > maxPage) maxPage = o.page
                l.items.add(o)
            }
            for (i in 0 until dock.length()) {
                l.dock.add(itemFromJson(dock.getJSONObject(i)))
            }
            l.pageCount = maxOf(root.optInt("pages", 1), maxPage + 1, 1)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun itemToJson(o: HomeItem): JSONObject {
        val j = JSONObject()
        j.put("id", o.id)
        j.put("t", o.type)
        j.put("p", o.page)
        j.put("r", o.row)
        j.put("c", o.col)
        j.put("w", o.spanW)
        j.put("h", o.spanH)
        j.put("comp", o.component)
        j.put("name", o.name)
        j.put("wid", o.widgetId)
        val kids = JSONArray()
        for (k in o.children) kids.put(k)
        j.put("kids", kids)
        return j
    }

    private fun itemFromJson(j: JSONObject): HomeItem {
        val o = HomeItem(j.getString("id"), j.getInt("t"))
        o.page = j.optInt("p", 0)
        o.row = j.optInt("r", 0)
        o.col = j.optInt("c", 0)
        o.spanW = j.optInt("w", 1)
        o.spanH = j.optInt("h", 1)
        o.component = j.optString("comp", "")
        o.name = j.optString("name", "")
        o.widgetId = j.optInt("wid", -1)
        val kids = j.optJSONArray("kids")
        if (kids != null) {
            for (i in 0 until kids.length()) o.children.add(kids.getString(i))
        }
        return o
    }
}
