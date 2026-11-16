package com.clawmate.platform.files

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import com.wangzan101.mika.core.cap.devicefiles.DeviceFileEntry
import com.wangzan101.mika.core.cap.devicefiles.DeviceFileImport
import com.wangzan101.mika.core.cap.devicefiles.DeviceFilePage
import com.wangzan101.mika.core.cap.devicefiles.DeviceFileSearchQuery
import com.wangzan101.mika.core.cap.devicefiles.DeviceFileSearchResult
import com.wangzan101.mika.core.cap.devicefiles.DeviceFilesPort
import com.wangzan101.mika.core.workspace.SandboxError

/**
 * 只读的设备文件端口：查询用户通过 SAF 授权的那一棵目录树。
 *
 * - 路径参数是 documentId（SAF 的不透明标识），不是文件系统路径；
 * - 每页上限 100，按名称+修改时间稳定排序；
 * - 不写、不删、不导入：`importFile` 在沙箱接入前统一返回 `unsupported`。
 */
class AndroidDeviceFilesPort(private val context: Context, private val tree: Uri) : DeviceFilesPort {
    private val resolver get() = context.contentResolver
    private val rootId: String get() = DocumentsContract.getTreeDocumentId(tree)

    override suspend fun list(path: String?, cursor: String?, limit: Int): DeviceFilePage {
        val parent = path?.takeIf { it.isNotBlank() } ?: rootId
        return pageOf(children(parent, 5_000), cursor, limit)
    }

    override suspend fun search(query: DeviceFileSearchQuery): DeviceFileSearchResult {
        val started = SystemClock.elapsedRealtime()
        val root = query.path.takeIf { it.isNotBlank() } ?: rootId
        val found = mutableListOf<DeviceFileEntry>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue += root to 0
        var visited = 0
        var truncated = false
        var reason: String? = null
        while (queue.isNotEmpty()) {
            if (found.size >= query.maxEntries) { truncated = true; reason = "entry_limit"; break }
            if (SystemClock.elapsedRealtime() - started > query.timeoutMs) { truncated = true; reason = "timeout"; break }
            val (dir, depth) = queue.removeFirst()
            visited++
            for (entry in children(dir, 2_000)) {
                if (found.size >= query.maxEntries) { truncated = true; reason = "entry_limit"; break }
                if (matches(entry, query)) found += entry
                if (entry.isDirectory && depth < query.maxDepth.coerceAtLeast(0)) queue += entry.path to depth + 1
            }
            if (truncated) break
        }
        return DeviceFileSearchResult(found, visited, truncated, reason)
    }

    override suspend fun importFile(path: String, workspace: String): DeviceFileImport =
        throw SandboxError("unsupported", "导入文件需要工作区沙箱；当前版本只支持列出和读取。", true)

    private fun matches(entry: DeviceFileEntry, query: DeviceFileSearchQuery): Boolean {
        val name = query.name?.trim().orEmpty()
        if (name.isNotEmpty() && !entry.name.contains(name, ignoreCase = true)) return false
        val ext = query.extension?.trim()?.removePrefix(".").orEmpty()
        if (ext.isNotEmpty() && !entry.name.substringAfterLast('.', "").equals(ext, ignoreCase = true)) return false
        val after = query.modifiedAfter
        if (after != null && (entry.modifiedAt ?: 0) < after) return false
        val before = query.modifiedBefore
        if (before != null && (entry.modifiedAt ?: Long.MAX_VALUE) > before) return false
        return true
    }

    private fun pageOf(all: List<DeviceFileEntry>, cursor: String?, limit: Int): DeviceFilePage {
        val size = limit.coerceIn(1, 100)
        val sorted = all.sortedWith(compareBy<DeviceFileEntry> { it.name.lowercase() }
            .thenByDescending { it.modifiedAt ?: 0 }.thenBy { it.path })
        val start = if (cursor.isNullOrEmpty()) 0 else
            sorted.indexOfFirst { it.path == cursor }.takeIf { it >= 0 }?.plus(1) ?: 0
        val slice = sorted.drop(start).take(size)
        val next = if (start + slice.size < sorted.size && slice.isNotEmpty()) slice.last().path else null
        return DeviceFilePage(slice, next)
    }

    private fun children(parentId: String, bounded: Int): List<DeviceFileEntry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = ArrayList<DeviceFileEntry>()
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val timeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext() && out.size < bounded) {
                val id = cursor.getString(idCol) ?: continue
                val mime = cursor.getString(mimeCol)
                out += DeviceFileEntry(
                    name = cursor.getString(nameCol).orEmpty().take(200),
                    path = id,
                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    size = if (sizeCol >= 0) cursor.getLong(sizeCol) else null,
                    modifiedAt = if (timeCol >= 0) cursor.getLong(timeCol) else null,
                )
            }
        }
        return out
    }
}
