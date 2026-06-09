package com.dschat.app.agent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.dschat.app.agent.Tool
import com.dschat.app.agent.arrayProp
import com.dschat.app.agent.boolOr
import com.dschat.app.agent.boolProp
import com.dschat.app.agent.intProp
import com.dschat.app.agent.objectSchema
import com.dschat.app.agent.str
import com.dschat.app.agent.strOrNull
import com.dschat.app.agent.strProp
import com.dschat.app.util.DocumentTextExtractor
import com.dschat.app.util.DocxBuilder
import com.dschat.app.util.ImagePdfBuilder
import com.dschat.app.util.PdfUtils
import com.dschat.app.util.TextPdfBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---- shared helpers (all converted files land in the public 下载 dir, then optional share) ----

private fun downloadDir(): File = File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS)

private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

/** Build an output file under 下载, sanitizing [rawName] or falling back to [prefix]_<timestamp>. */
private fun outFile(rawName: String?, prefix: String, ext: String): File {
    val base = rawName?.trim()?.takeIf { it.isNotEmpty() }
        ?.substringBeforeLast('.')
        ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        ?: "${prefix}_${stamp()}"
    return File(downloadDir(), "$base.$ext")
}

private fun shareSingle(context: Context, file: File, mime: String, share: Boolean): String {
    if (!share) return ""
    return try {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "，并已弹出分享菜单"
    } catch (e: Exception) {
        "（已保存，但分享菜单弹出失败：${e.message}）"
    }
}

private fun shareMultiple(context: Context, files: List<File>, mime: String, share: Boolean): String {
    if (!share || files.isEmpty()) return ""
    return try {
        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, context.packageName + ".fileprovider", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "，并已弹出分享菜单"
    } catch (e: Exception) {
        "（已保存，但分享菜单弹出失败：${e.message}）"
    }
}

/** Extract readable text from a document path (.pdf/.docx/.pptx/.xlsx via extractor, else plain UTF-8). */
private fun extractDocText(context: Context, file: File): String? =
    if (DocumentTextExtractor.isSupported(file.name)) {
        DocumentTextExtractor.extract(context, Uri.fromFile(file), file.name)
    } else {
        try { file.readText(Charsets.UTF_8).takeIf { it.isNotBlank() } } catch (_: Exception) { null }
    }

private fun imagePaths(args: JsonObject, key: String): List<String> =
    (args[key] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()

// ---- 图片 → PDF ----

class ImageToPdfTool(private val context: Context) : Tool {
    override val name = "image_to_pdf"
    override val description =
        "把一张或多张本机图片合成为一个 PDF 文件：保存到手机「下载」目录，并弹出系统分享菜单方便发出去。" +
        "当用户发来图片（消息上下文里会给出图片的本机绝对路径）并要求“转成/导出/保存为 PDF”时调用本工具。"
    override val sideEffect = true

    override fun parameters(): JsonObject = objectSchema(
        "image_paths" to arrayProp("要转换的图片本机绝对路径数组，按页码顺序排列。通常是用户刚发来的那张图片的路径。"),
        "file_name" to strProp("可选：输出 PDF 的文件名（不含扩展名）。留空则按时间自动生成。"),
        "share" to boolProp("生成后是否弹出系统分享菜单。默认 true。"),
        required = listOf("image_paths")
    )

    override suspend fun execute(args: JsonObject): String {
        val paths = imagePaths(args, "image_paths")
        if (paths.isEmpty()) return "错误：image_paths 为空。请提供图片的本机绝对路径。"
        val missing = paths.filter { !File(it).exists() }
        if (missing.isNotEmpty())
            return "错误：找不到这些图片文件：${missing.joinToString("、")}。请确认路径无误。"

        val out = outFile(args.strOrNull("file_name"), "图片转换", "pdf")
        val pages = try {
            withContext(Dispatchers.IO) { ImagePdfBuilder.build(paths, out) }
        } catch (e: Exception) {
            return "生成 PDF 失败：${e.message}"
        }
        val note = shareSingle(context, out, "application/pdf", args.boolOr("share", true))
        return "已生成 PDF：${out.absolutePath}（共 $pages 页，约 ${out.length() / 1024}KB）$note"
    }
}

// ---- 文档(Word/PPT/Excel/txt/md) → PDF ----

class DocumentToPdfTool(private val context: Context) : Tool {
    override val name = "document_to_pdf"
    override val description =
        "把本机的 Word(.docx)/PPT(.pptx)/Excel(.xlsx)/文本(.txt)/Markdown(.md) 文档转换成 PDF，保存到「下载」目录并可分享。" +
        "用于“Word 转 PDF”“文本转 PDF”等。⚠️离线转换只保留文字内容，不保留原排版/表格/图片。"
    override val sideEffect = true

    override fun parameters(): JsonObject = objectSchema(
        "source_path" to strProp("要转换的文档的本机绝对路径（.docx/.pptx/.xlsx/.txt/.md）。用户上传的文件路径会在消息上下文给出。"),
        "file_name" to strProp("可选：输出 PDF 文件名（不含扩展名）。留空则沿用源文件名。"),
        "share" to boolProp("生成后是否弹出系统分享菜单。默认 true。"),
        required = listOf("source_path")
    )

    override suspend fun execute(args: JsonObject): String {
        val src = File(args.str("source_path"))
        if (!src.exists()) return "错误：找不到文件 ${src.path}。"
        val text = withContext(Dispatchers.IO) { extractDocText(context, src) }
            ?: return "错误：无法从该文档读取文字（可能是不支持的格式或扫描件）。"
        val out = outFile(args.strOrNull("file_name") ?: src.name, "文档转换", "pdf")
        val pages = try {
            withContext(Dispatchers.IO) { TextPdfBuilder.build(text, out) }
        } catch (e: Exception) {
            return "生成 PDF 失败：${e.message}"
        }
        val note = shareSingle(context, out, "application/pdf", args.boolOr("share", true))
        return "已把「${src.name}」转换为 PDF：${out.absolutePath}（共 $pages 页）。注意：仅保留文字，未保留原排版/图片$note"
    }
}

// ---- PDF → Word(.docx) ----

class PdfToWordTool(private val context: Context) : Tool {
    override val name = "pdf_to_word"
    override val description =
        "把本机的 PDF 转换成 Word(.docx)：提取 PDF 中的文字生成可编辑的 Word 文档，保存到「下载」并可分享。" +
        "⚠️只提取文字，不保留排版/图片/表格；扫描件（图片型 PDF）无可提取文字。"
    override val sideEffect = true

    override fun parameters(): JsonObject = objectSchema(
        "source_path" to strProp("要转换的 PDF 的本机绝对路径。"),
        "file_name" to strProp("可选：输出 Word 文件名（不含扩展名）。留空则沿用源文件名。"),
        "share" to boolProp("生成后是否弹出系统分享菜单。默认 true。"),
        required = listOf("source_path")
    )

    override suspend fun execute(args: JsonObject): String {
        val src = File(args.str("source_path"))
        if (!src.exists()) return "错误：找不到文件 ${src.path}。"
        if (!src.name.lowercase().endsWith(".pdf")) return "错误：该工具只接受 PDF 文件。"
        val text = withContext(Dispatchers.IO) { extractDocText(context, src) }
        if (text.isNullOrBlank())
            return "该 PDF 没有可提取的文字（很可能是扫描件/图片型 PDF）。可改用 pdf_to_images 转成图片。"
        val out = outFile(args.strOrNull("file_name") ?: src.name, "PDF转Word", "docx")
        try {
            withContext(Dispatchers.IO) { DocxBuilder.build(text, out) }
        } catch (e: Exception) {
            return "生成 Word 失败：${e.message}"
        }
        val note = shareSingle(
            context, out,
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            args.boolOr("share", true)
        )
        return "已把「${src.name}」转换为 Word：${out.absolutePath}（约 ${out.length() / 1024}KB）。注意：仅保留文字$note"
    }
}

// ---- PDF → 图片(PNG，每页一张) ----

class PdfToImagesTool(private val context: Context) : Tool {
    override val name = "pdf_to_images"
    override val description =
        "把本机 PDF 的每一页渲染成图片(PNG)，保存到「下载」目录（最多 50 页），并可批量分享。用于“PDF 转图片”。"
    override val sideEffect = true

    override fun parameters(): JsonObject = objectSchema(
        "source_path" to strProp("要转换的 PDF 的本机绝对路径。"),
        "dpi" to intProp("渲染清晰度（每英寸像素），默认 150；越大越清晰、文件越大。", 72, 300),
        "share" to boolProp("生成后是否弹出系统分享菜单。默认 true。"),
        required = listOf("source_path")
    )

    override suspend fun execute(args: JsonObject): String {
        val src = File(args.str("source_path"))
        if (!src.exists()) return "错误：找不到文件 ${src.path}。"
        val dpi = ((args["dpi"] as? JsonPrimitive)?.intOrNull ?: 150).coerceIn(72, 300).toFloat()
        val base = src.name.substringBeforeLast('.').replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val (paths, total) = try {
            withContext(Dispatchers.IO) {
                val total = PdfUtils.pageCount(src)
                PdfUtils.toImages(src, downloadDir(), base, dpi) to total
            }
        } catch (e: Exception) {
            return "转换失败：${e.message}"
        }
        if (paths.isEmpty()) return "未能渲染任何页面。"
        val note = shareMultiple(context, paths.map { File(it) }, "image/png", args.boolOr("share", true))
        val capped = if (total > paths.size) "（共 $total 页，已渲染前 ${paths.size} 页）" else ""
        return "已把「${src.name}」转换为 ${paths.size} 张图片，保存在「下载」目录$capped$note\n${paths.joinToString("\n")}"
    }
}

// ---- 合并多个 PDF ----

class MergePdfsTool(private val context: Context) : Tool {
    override val name = "merge_pdfs"
    override val description = "把多个本机 PDF 按给定顺序合并成一个 PDF，保存到「下载」并可分享。"
    override val sideEffect = true

    override fun parameters(): JsonObject = objectSchema(
        "pdf_paths" to arrayProp("要合并的 PDF 的本机绝对路径数组，按希望的先后顺序排列。"),
        "file_name" to strProp("可选：输出文件名（不含扩展名）。留空则按时间生成。"),
        "share" to boolProp("生成后是否弹出系统分享菜单。默认 true。"),
        required = listOf("pdf_paths")
    )

    override suspend fun execute(args: JsonObject): String {
        val paths = imagePaths(args, "pdf_paths")
        if (paths.size < 2) return "错误：至少需要 2 个 PDF 才能合并。"
        val files = paths.map { File(it) }
        val missing = files.filter { !it.exists() }
        if (missing.isNotEmpty()) return "错误：找不到这些文件：${missing.joinToString("、") { it.name }}。"

        val out = outFile(args.strOrNull("file_name"), "合并", "pdf")
        try {
            withContext(Dispatchers.IO) { PdfUtils.merge(files, out) }
        } catch (e: Exception) {
            return "合并失败：${e.message}"
        }
        val note = shareSingle(context, out, "application/pdf", args.boolOr("share", true))
        return "已合并 ${files.size} 个 PDF：${out.absolutePath}（约 ${out.length() / 1024}KB）$note"
    }
}

// ---- PDF 拆分 / 提取指定页 ----

class SplitPdfTool(private val context: Context) : Tool {
    override val name = "split_pdf"
    override val description =
        "从本机 PDF 中提取指定页码生成新的 PDF（页码从 1 开始，支持区间，如“1-3,5,8-10”），保存到「下载」并可分享。"
    override val sideEffect = true

    override fun parameters(): JsonObject = objectSchema(
        "source_path" to strProp("源 PDF 的本机绝对路径。"),
        "pages" to strProp("要提取的页码，1 起；逗号分隔、可用区间，如 “1-3,5,8-10”。"),
        "file_name" to strProp("可选：输出文件名（不含扩展名）。留空则按时间生成。"),
        "share" to boolProp("生成后是否弹出系统分享菜单。默认 true。"),
        required = listOf("source_path", "pages")
    )

    override suspend fun execute(args: JsonObject): String {
        val src = File(args.str("source_path"))
        if (!src.exists()) return "错误：找不到文件 ${src.path}。"
        val pages = PdfUtils.parsePages(args.str("pages"))
        if (pages.isEmpty()) return "错误：页码无法解析。示例：1-3,5"
        val out = outFile(args.strOrNull("file_name"), "拆分", "pdf")
        val n = try {
            withContext(Dispatchers.IO) { PdfUtils.extractPages(src, pages, out) }
        } catch (e: Exception) {
            return "拆分失败：${e.message}"
        }
        val note = shareSingle(context, out, "application/pdf", args.boolOr("share", true))
        return "已从「${src.name}」提取 $n 页生成新 PDF：${out.absolutePath}$note"
    }
}
