package com.dschat.app.agent.tools

import android.os.Environment
import com.dschat.app.agent.Tool
import com.dschat.app.agent.boolOr
import com.dschat.app.agent.boolProp
import com.dschat.app.agent.intOr
import com.dschat.app.agent.intProp
import com.dschat.app.agent.objectSchema
import com.dschat.app.agent.str
import com.dschat.app.agent.strOrNull
import com.dschat.app.agent.strProp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.Locale

private fun resolvePath(path: String): File =
    if (path.startsWith("/")) File(path) else File(Environment.getExternalStorageDirectory(), path)

class ReadFileTool : Tool {
    override val name = "read_file"
    override val description = "读取文本文件内容。path 可为绝对路径（如 /sdcard/Download/a.txt）或相对外部存储根目录的路径。"
    override val sideEffect = false
    override fun parameters() = objectSchema(
        "path" to strProp("文件路径"),
        "max_chars" to intProp("最大返回字符数，默认 20000"),
        required = listOf("path")
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val f = resolvePath(args.str("path"))
        when {
            !f.exists() -> "错误：文件不存在：${f.absolutePath}"
            f.isDirectory -> "错误：这是目录，请用 list_files"
            f.length() > 4 * 1024 * 1024 -> "错误：文件过大（>4MB），无法直接读取"
            else -> try {
                val limit = args.intOr("max_chars", 20000).coerceIn(100, 100000)
                val text = f.readText()
                if (text.length > limit) text.take(limit) + "\n…（已截断，共 ${text.length} 字符）" else text
            } catch (e: Exception) {
                "读取失败：${e.message}"
            }
        }
    }
}

class WriteFileTool : Tool {
    override val name = "write_file"
    override val description = "把文本写入文件（默认覆盖，可追加）。会自动创建上级目录。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "path" to strProp("文件路径"),
        "content" to strProp("要写入的文本内容"),
        "append" to boolProp("是否追加而非覆盖，默认 false"),
        required = listOf("path", "content")
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val f = resolvePath(args.str("path"))
        val content = args.str("content")
        val append = args.boolOr("append", false)
        try {
            f.parentFile?.mkdirs()
            if (append) f.appendText(content) else f.writeText(content)
            "已写入 ${f.absolutePath}（${content.length} 字符${if (append) "，追加" else ""}）"
        } catch (e: Exception) {
            "写入失败：${e.message}"
        }
    }
}

class ListFilesTool : Tool {
    override val name = "list_files"
    override val description = "列出某个目录下的文件与子目录。"
    override val sideEffect = false
    override fun parameters() = objectSchema(
        "path" to strProp("目录路径，默认外部存储根目录"),
        required = emptyList()
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val path = args.str("path").ifBlank { Environment.getExternalStorageDirectory().absolutePath }
        val dir = resolvePath(path)
        when {
            !dir.exists() -> "错误：路径不存在：${dir.absolutePath}"
            !dir.isDirectory -> "错误：这不是目录：${dir.absolutePath}"
            else -> {
                val raw = dir.listFiles()
                    ?: return@withContext "无法列出（可能无权限）：${dir.absolutePath}"
                if (raw.isEmpty()) return@withContext "（空目录）${dir.absolutePath}"
                // For big dirs, skip the full sort (sorting thousands is slow) — just take the first 200.
                val big = raw.size > 500
                val entries = if (big) raw.asSequence().take(200).toList()
                    else raw.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                buildString {
                    append(dir.absolutePath).append(" 共 ${raw.size} 项")
                    if (big) append("（数量较多，未排序，仅列前 200）")
                    append("：\n")
                    entries.take(200).forEach { e ->
                        if (e.isDirectory) append("📁 ").append(e.name).append("/\n")
                        else append("📄 ").append(e.name).append("  (").append(e.length()).append(" bytes)\n")
                    }
                }.trim()
            }
        }
    }
}

class DeleteFileTool : Tool {
    override val name = "delete_file"
    override val description = "删除文件或目录（目录需 recursive=true 才会递归删除）。此操作不可恢复。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "path" to strProp("要删除的路径"),
        "recursive" to boolProp("若为目录，是否递归删除，默认 false"),
        required = listOf("path")
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val f = resolvePath(args.str("path"))
        if (!f.exists()) return@withContext "错误：路径不存在：${f.absolutePath}"
        val recursive = args.boolOr("recursive", false)
        try {
            val ok = if (f.isDirectory && recursive) f.deleteRecursively() else f.delete()
            if (ok) "已删除：${f.absolutePath}" else "删除失败（若是非空目录请用 recursive=true）：${f.absolutePath}"
        } catch (e: Exception) {
            "删除失败：${e.message}"
        }
    }
}

class FindFilesTool : Tool {
    override val name = "find_files"
    override val description =
        "递归搜索一个目录，按文件大小从大到小返回匹配的文件。用于『找最大的文件/文档』这类需求——一次调用即可，不要用 list_files 逐个目录翻找。"
    override val sideEffect = false
    override fun parameters() = objectSchema(
        "path" to strProp("起始目录，默认外部存储根目录"),
        "name_contains" to strProp("可选：文件名包含的关键字"),
        "extensions" to strProp("可选：扩展名，逗号分隔，如 pdf,docx,txt,xlsx"),
        "min_size_kb" to intProp("可选：最小大小(KB)，默认 0"),
        "max_results" to intProp("返回数量，默认 20"),
        required = emptyList()
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val root = resolvePath(args.str("path").ifBlank { Environment.getExternalStorageDirectory().absolutePath })
        if (!root.exists() || !root.isDirectory) return@withContext "错误：目录不存在：${root.absolutePath}"
        val nameContains = args.strOrNull("name_contains")?.lowercase()?.takeIf { it.isNotBlank() }
        val exts = args.strOrNull("extensions").orEmpty()
            .split(",").map { it.trim().removePrefix(".").lowercase() }.filter { it.isNotEmpty() }.toSet()
        val minBytes = args.intOr("min_size_kb", 0).toLong() * 1024L
        val limit = args.intOr("max_results", 20).coerceIn(1, 100)

        val matches = ArrayList<File>()
        var scanned = 0
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty() && scanned < 60000) {
            val children = stack.removeLast().listFiles() ?: continue
            for (f in children) {
                if (f.isDirectory) {
                    stack.add(f)
                } else {
                    scanned++
                    if (f.length() < minBytes) continue
                    if (nameContains != null && !f.name.lowercase().contains(nameContains)) continue
                    if (exts.isNotEmpty() && f.extension.lowercase() !in exts) continue
                    matches.add(f)
                }
            }
        }
        matches.sortByDescending { it.length() }
        val top = matches.take(limit)
        if (top.isEmpty()) return@withContext "在 ${root.absolutePath} 下没找到匹配文件（已扫描 $scanned 个文件）。"
        buildString {
            append("在 ${root.absolutePath} 下共 ${matches.size} 个匹配（扫描 $scanned 个文件），最大的 ${top.size} 个：\n")
            top.forEach { f ->
                val mb = f.length() / 1024.0 / 1024.0
                append("• ").append(String.format(Locale.US, "%.2f MB", mb)).append("  ").append(f.absolutePath).append('\n')
            }
        }.trim()
    }
}
