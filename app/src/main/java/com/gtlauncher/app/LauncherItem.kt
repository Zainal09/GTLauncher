package com.gtlauncher.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ItemType {
    APP,
    FOLDER,
    WIDGET
}

data class LauncherItem(
    val id: String = UUID.randomUUID().toString(),
    var type: ItemType = ItemType.APP,
    var page: Int = 0,
    var cellX: Int = 0,
    var cellY: Int = 0,
    var spanX: Int = 1,
    var spanY: Int = 1,
    var packageName: String = "",
    var componentName: String = "",
    var customTitle: String? = null,
    var customIcon: String? = null,
    var widgetId: Int? = null,
    var highlight: Boolean = false,
    val folderItems: MutableList<LauncherItem> = mutableListOf()
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("type", type.name)
        obj.put("page", page)
        obj.put("cellX", cellX)
        obj.put("cellY", cellY)
        obj.put("spanX", spanX)
        obj.put("spanY", spanY)
        obj.put("packageName", packageName)
        obj.put("componentName", componentName)
        if (customTitle != null) obj.put("customTitle", customTitle)
        if (customIcon != null) obj.put("customIcon", customIcon)
        if (widgetId != null) obj.put("widgetId", widgetId)
        if (folderItems.isNotEmpty()) {
            val arr = JSONArray()
            folderItems.forEach { arr.put(it.toJson()) }
            obj.put("folderItems", arr)
        }
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): LauncherItem {
            val typeStr = obj.optString("type", "APP")
            val itemType = try {
                ItemType.valueOf(typeStr)
            } catch (e: Exception) {
                ItemType.APP
            }

            val item = LauncherItem(
                id = obj.optString("id", UUID.randomUUID().toString()),
                type = itemType,
                page = obj.optInt("page", 0),
                cellX = obj.optInt("cellX", 0),
                cellY = obj.optInt("cellY", 0),
                spanX = obj.optInt("spanX", 1).coerceAtLeast(1),
                spanY = obj.optInt("spanY", 1).coerceAtLeast(1),
                packageName = obj.optString("packageName", ""),
                componentName = obj.optString("componentName", ""),
                customTitle = if (obj.has("customTitle")) obj.optString("customTitle") else null,
                customIcon = if (obj.has("customIcon")) obj.optString("customIcon") else null,
                widgetId = if (obj.has("widgetId")) obj.optInt("widgetId") else null
            )

            val arr = obj.optJSONArray("folderItems")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val childObj = arr.optJSONObject(i)
                    if (childObj != null) {
                        item.folderItems.add(fromJson(childObj))
                    }
                }
            }
            return item
        }
    }
}
