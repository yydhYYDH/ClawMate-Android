package com.clawmate.platform.files

/**
 * 设备文件端口要用的纯逻辑：路径规范化、名称搜索与分页。
 *
 * SAF 的具体查询（DocumentsContract）依赖 ContentResolver，放在 Android 层；
 * 这里只放可单测的部分，C4 的端口实现直接复用。
 */
data class DeviceFileItem(
    val id: String,
    val name: String,
    val mime: String?,
    val size: Long,
    val modifiedAt: Long,
)

data class DeviceFilePage(val items: List<DeviceFileItem>, val nextCursor: String?)

object DeviceFileIndex {
    const val MAX_PAGE = 100

    /** 只做分隔符统一与去尾斜杠；不解析 `..`（SAF 的 documentId 才是真实路径）。 */
    fun normalizePath(path: String?): String = path.orEmpty().replace('\\', '/').trim('/')

    /** 名称包含匹配，忽略大小写；空查询表示不过滤。 */
    fun matches(item: DeviceFileItem, query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        if (item.name.contains(needle, ignoreCase = true)) return true
        val mime = item.mime.orEmpty()
        return mime.isNotEmpty() && mime.contains(needle, ignoreCase = true)
    }

    /**
     * 按键（名称、再按修改时间倒序）稳定排序后分页；[cursor] 是上一页最后一个 id。
     * 返回 null 表示游标已失效（调用方从头开始）。
     */
    fun page(items: List<DeviceFileItem>, cursor: String?, limit: Int): DeviceFilePage {
        val size = limit.coerceIn(1, MAX_PAGE)
        val sorted = items.sortedWith(compareBy<DeviceFileItem> { it.name.lowercase() }
            .thenByDescending { it.modifiedAt }.thenBy { it.id })
        val start = if (cursor.isNullOrEmpty()) 0 else
            sorted.indexOfFirst { it.id == cursor }.takeIf { it >= 0 }?.plus(1) ?: 0
        val slice = sorted.drop(start).take(size)
        val next = if (start + slice.size < sorted.size && slice.isNotEmpty()) slice.last().id else null
        return DeviceFilePage(slice, next)
    }

    /** 搜索：先过滤再分页；空结果给空页而不是报错。 */
    fun search(items: List<DeviceFileItem>, query: String, cursor: String?, limit: Int): DeviceFilePage =
        page(items.filter { matches(it, query) }, cursor, limit)
}
