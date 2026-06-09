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
import com.dschat.app.agent.ToolLimits
import com.dschat.app.agent.capNote
import com.dschat.app.agent.strProp
import com.dschat.app.agent.arrayProp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private fun resolvePath(path: String): File =
    if (path.startsWith("/")) File(path) else File(Environment.getExternalStorageDirectory(), path)

private fun downloadDirOf(): File =
    File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS)

private fun fileStamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

private fun pathArray(args: JsonObject, key: String): List<String> =
    (args[key] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()

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

private const val MAX_WRITE_CHARS = 2_000_000 // ~ a few MB; guards against the model filling storage

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
        if (content.length > MAX_WRITE_CHARS) {
            return@withContext "错误：内容过大（${content.length} 字符，上限 $MAX_WRITE_CHARS）。请分批写入或精简后再写。"
        }
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
        "offset" to intProp("从第几条开始返回（翻页用，默认 0）", 0, null),
        required = emptyList()
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val path = args.str("path").ifBlank { Environment.getExternalStorageDirectory().absolutePath }
        val offset = args.intOr("offset", 0).coerceAtLeast(0)
        val dir = resolvePath(path)
        when {
            !dir.exists() -> "错误：路径不存在：${dir.absolutePath}"
            !dir.isDirectory -> "错误：这不是目录：${dir.absolutePath}"
            else -> {
                val raw = dir.listFiles()
                    ?: return@withContext "无法列出（可能无权限）：${dir.absolutePath}"
                if (raw.isEmpty()) return@withContext "（空目录）${dir.absolutePath}"
                val sorted = raw.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                val page = sorted.drop(offset).take(ToolLimits.LIST_CAP)
                buildString {
                    append(dir.absolutePath).append(" 共 ${raw.size} 项：\n")
                    page.forEach { e ->
                        if (e.isDirectory) append("📁 ").append(e.name).append("/\n")
                        else append("📄 ").append(e.name).append("  (").append(e.length()).append(" bytes)\n")
                    }
                    val reached = offset + page.size
                    append(capNote(reached, raw.size, "，用 offset=$reached 继续翻页"))
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
        "min_size_kb" to intProp("可选：最小大小(KB)，默认 0", 0, null),
        "max_results" to intProp("返回数量，默认 20", 1, 100),
        "offset" to intProp("从第几条开始返回（翻页用，默认 0）", 0, null),
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
        val offset = args.intOr("offset", 0).coerceAtLeast(0)

        val matches = ArrayList<File>()
        var scanned = 0
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty() && scanned < ToolLimits.FIND_SCAN_CAP) {
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
        val capped = scanned >= ToolLimits.FIND_SCAN_CAP
        val scanNote = if (capped) "，已达扫描上限、可能未覆盖全部，可缩小 path 或加过滤" else ""
        val top = matches.drop(offset).take(limit)
        if (top.isEmpty()) return@withContext "在 ${root.absolutePath} 下没找到匹配文件（已扫描 $scanned 个文件$scanNote）。"
        buildString {
            append("在 ${root.absolutePath} 下共 ${matches.size} 个匹配（扫描 $scanned 个文件$scanNote），按大小返回 ${top.size} 个：\n")
            top.forEach { f ->
                val mb = f.length() / 1024.0 / 1024.0
                append("• ").append(String.format(Locale.US, "%.2f MB", mb)).append("  ").append(f.absolutePath).append('\n')
            }
            val reached = offset + top.size
            append(capNote(reached, matches.size, "，用 offset=$reached 继续翻页"))
        }.trim()
    }
}

/** dest 为已存在目录时，把 [src] 放进去并沿用其文件名；否则就当成目标文件路径本身。 */
private fun resolveDest(destPath: String, src: File): File {
    val d = resolvePath(destPath)
    return if (d.isDirectory) File(d, src.name) else d
}

class CopyFileTool : Tool {
    override val name = "copy_file"
    override val description = "复制文件或目录到新位置（保留原件）。dest 若是已存在的目录，则复制进该目录并沿用原名。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "source" to strProp("源文件/目录路径"),
        "dest" to strProp("目标路径（文件路径，或一个已存在的目录）"),
        required = listOf("source", "dest")
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val src = resolvePath(args.str("source"))
        if (!src.exists()) return@withContext "错误：源不存在：${src.absolutePath}"
        val dst = resolveDest(args.str("dest"), src)
        if (src.absolutePath == dst.absolutePath) return@withContext "错误：源和目标相同。"
        try {
            dst.parentFile?.mkdirs()
            if (src.isDirectory) src.copyRecursively(dst, overwrite = true)
            else src.copyTo(dst, overwrite = true)
            "已复制到 ${dst.absolutePath}"
        } catch (e: Exception) {
            "复制失败：${e.message}"
        }
    }
}

class MoveFileTool : Tool {
    override val name = "move_file"
    override val description = "移动或重命名文件/目录。重命名＝同目录给新名字；移动＝换到别的目录。dest 若是已存在目录则移动进去并保留原名。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "source" to strProp("源文件/目录路径"),
        "dest" to strProp("目标路径（新文件名，或一个已存在的目录）"),
        required = listOf("source", "dest")
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val src = resolvePath(args.str("source"))
        if (!src.exists()) return@withContext "错误：源不存在：${src.absolutePath}"
        val dst = resolveDest(args.str("dest"), src)
        if (src.absolutePath == dst.absolutePath) return@withContext "错误：源和目标相同。"
        try {
            dst.parentFile?.mkdirs()
            if (dst.exists()) dst.deleteRecursively()
            if (src.renameTo(dst)) return@withContext "已移动到 ${dst.absolutePath}"
            // Cross-filesystem (e.g. 不同挂载点) → 复制后删除。
            if (src.isDirectory) src.copyRecursively(dst, overwrite = true) else src.copyTo(dst, overwrite = true)
            src.deleteRecursively()
            "已移动到 ${dst.absolutePath}"
        } catch (e: Exception) {
            "移动失败：${e.message}"
        }
    }
}

private const val ZIP_MAX_ENTRIES = 5000
private const val ZIP_MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024 // 2GB 上限，防失控

class CompressFilesTool : Tool {
    override val name = "compress_files"
    override val description = "把若干本机文件/目录打包成一个 zip 压缩包。output_path 留空则存到「下载」目录。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "paths" to arrayProp("要压缩的文件/目录路径数组"),
        "output_path" to strProp("可选：输出 zip 的路径；留空则存到「下载」目录、按时间命名。"),
        required = listOf("paths")
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val inputs = pathArray(args, "paths").map { resolvePath(it) }
        if (inputs.isEmpty()) return@withContext "错误：paths 为空。"
        val missing = inputs.filter { !it.exists() }
        if (missing.isNotEmpty()) return@withContext "错误：找不到：${missing.joinToString("、") { it.name }}"
        val out = args.strOrNull("output_path")?.takeIf { it.isNotBlank() }?.let { resolvePath(it) }
            ?: File(downloadDirOf(), "archive_${fileStamp()}.zip")
        try {
            out.parentFile?.mkdirs()
            var entries = 0
            var totalBytes = 0L
            ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zos ->
                fun addFile(file: File, entryName: String) {
                    if (entries >= ZIP_MAX_ENTRIES) throw IllegalStateException("条目过多（上限 $ZIP_MAX_ENTRIES）")
                    totalBytes += file.length()
                    if (totalBytes > ZIP_MAX_TOTAL_BYTES) throw IllegalStateException("内容过大（上限 2GB）")
                    zos.putNextEntry(ZipEntry(entryName))
                    FileInputStream(file).use { it.copyTo(zos) }
                    zos.closeEntry()
                    entries++
                }
                fun addRecursively(file: File, base: String) {
                    if (file.isDirectory) {
                        val children = file.listFiles() ?: return
                        if (children.isEmpty()) { zos.putNextEntry(ZipEntry("$base/")); zos.closeEntry() }
                        for (c in children) addRecursively(c, "$base/${c.name}")
                    } else addFile(file, base)
                }
                for (f in inputs) addRecursively(f, f.name)
            }
            "已压缩 $entries 个文件 → ${out.absolutePath}（约 ${out.length() / 1024}KB）"
        } catch (e: Exception) {
            out.delete()
            "压缩失败：${e.message}"
        }
    }
}

class ExtractArchiveTool : Tool {
    override val name = "extract_archive"
    override val description = "解压一个 zip 压缩包到目录。dest_dir 留空则解压到压缩包同级、以包名命名的新文件夹。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "archive_path" to strProp("要解压的 zip 文件路径"),
        "dest_dir" to strProp("可选：解压到的目标目录；留空则用压缩包同级、同名的新文件夹。"),
        required = listOf("archive_path")
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val src = resolvePath(args.str("archive_path"))
        if (!src.exists()) return@withContext "错误：找不到压缩包：${src.absolutePath}"
        if (!src.name.lowercase().endsWith(".zip")) return@withContext "错误：目前仅支持 .zip 压缩包。"
        val dest = args.strOrNull("dest_dir")?.takeIf { it.isNotBlank() }?.let { resolvePath(it) }
            ?: File(src.parentFile, src.nameWithoutExtension)
        try {
            dest.mkdirs()
            val destCanonical = dest.canonicalPath + File.separator
            var count = 0
            var totalBytes = 0L
            ZipInputStream(BufferedInputStream(FileInputStream(src))).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val target = File(dest, entry.name)
                    // Zip-slip 防护：解压目标必须落在 dest 内。
                    if (!(target.canonicalPath + File.separator).startsWith(destCanonical) &&
                        target.canonicalPath != dest.canonicalPath
                    ) throw SecurityException("压缩包含非法路径：${entry.name}")
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { fos -> totalBytes += zis.copyTo(fos) }
                        count++
                        if (count > ZIP_MAX_ENTRIES) throw IllegalStateException("条目过多（上限 $ZIP_MAX_ENTRIES）")
                        if (totalBytes > ZIP_MAX_TOTAL_BYTES) throw IllegalStateException("解压内容过大（上限 2GB）")
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            "已解压 $count 个文件 → ${dest.absolutePath}"
        } catch (e: Exception) {
            "解压失败：${e.message}"
        }
    }
}
