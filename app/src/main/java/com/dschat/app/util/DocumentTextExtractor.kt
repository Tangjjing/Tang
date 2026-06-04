package com.dschat.app.util

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.util.zip.ZipInputStream

/**
 * Offline text extraction for picked documents (no OCR — scanned/image-only files yield no text):
 *  - PDF  → PdfBox-Android's PDFTextStripper.
 *  - DOCX / PPTX / XLSX → parsed straight from the file's own OOXML (each is a ZIP), no extra deps.
 */
object DocumentTextExtractor {

    fun isSupported(name: String): Boolean {
        val l = name.lowercase()
        return l.endsWith(".pdf") || l.endsWith(".docx") || l.endsWith(".pptx") || l.endsWith(".xlsx")
    }

    /** Extracted text, or null if unsupported / extraction failed / no readable text. */
    fun extract(context: Context, uri: Uri, name: String): String? {
        val l = name.lowercase()
        val text = when {
            l.endsWith(".pdf") -> extractPdf(context, uri)
            l.endsWith(".docx") -> extractDocx(context, uri)
            l.endsWith(".pptx") -> extractPptx(context, uri)
            l.endsWith(".xlsx") -> extractXlsx(context, uri)
            else -> null
        }
        return text?.trim()?.ifBlank { null }
    }

    private fun extractPdf(context: Context, uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { ins ->
            PDDocument.load(ins).use { doc -> PDFTextStripper().getText(doc) }
        }
    } catch (e: Exception) {
        null
    }

    // ---- OOXML (zip) helpers ----

    // ZipBomb / OOM guards: a malicious OOXML could declare tiny compressed parts that inflate to GBs.
    private const val MAX_ZIP_ENTRY_BYTES = 20_000_000  // per part
    private const val MAX_ZIP_TOTAL_BYTES = 60_000_000  // across all wanted parts

    /** Read every zip entry whose name matches [want] into a name→UTF-8-text map, with size caps. */
    private fun readZip(context: Context, uri: Uri, want: (String) -> Boolean): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        var total = 0L
        try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                ZipInputStream(ins).use { zip ->
                    var e = zip.nextEntry
                    while (e != null) {
                        if (!e.isDirectory && want(e.name)) {
                            val bytes = readCapped(zip, MAX_ZIP_ENTRY_BYTES)
                            map[e.name] = bytes.toString(Charsets.UTF_8)
                            total += bytes.size
                            if (total > MAX_ZIP_TOTAL_BYTES) break
                        }
                        e = zip.nextEntry
                    }
                }
            }
        } catch (_: Exception) {
        } catch (_: OutOfMemoryError) {
        }
        return map
    }

    /** Read at most [limit] bytes from [input] (the current zip entry), so an inflated part can't OOM. */
    private fun readCapped(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var n = input.read(buf)
        while (n >= 0 && out.size() < limit) {
            out.write(buf, 0, n)
            n = input.read(buf)
        }
        return out.toByteArray()
    }

    private val WT = Regex("<w:t\\b[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)   // docx run
    private val WP = Regex("<w:p\\b.*?</w:p>", RegexOption.DOT_MATCHES_ALL)            // docx paragraph
    private val AT = Regex("<a:t\\b[^>]*>(.*?)</a:t>", RegexOption.DOT_MATCHES_ALL)    // pptx run
    private val SI = Regex("<si\\b[^>]*>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)      // xlsx shared-string item
    private val T = Regex("<t\\b[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)         // xlsx <t>
    private val ROW = Regex("<row\\b[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
    private val CELL = Regex("<c\\b[^>]*?(?:/>|>.*?</c>)", RegexOption.DOT_MATCHES_ALL)
    private val V = Regex("<v\\b[^>]*>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
    private val T_ATTR = Regex("\\bt=\"([^\"]*)\"")
    private val NUM = Regex("(\\d+)")

    private fun extractDocx(context: Context, uri: Uri): String? {
        val xml = readZip(context, uri) { it == "word/document.xml" }["word/document.xml"] ?: return null
        val out = StringBuilder()
        for (p in WP.findAll(xml)) {
            for (r in WT.findAll(p.value)) out.append(unescapeXml(r.groupValues[1]))
            out.append('\n')
        }
        if (out.isBlank()) for (r in WT.findAll(xml)) out.append(unescapeXml(r.groupValues[1])).append(' ')
        return out.toString()
    }

    private fun extractPptx(context: Context, uri: Uri): String? {
        val slides = readZip(context, uri) { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
        if (slides.isEmpty()) return null
        val ordered = slides.entries.sortedBy { numIn(it.key) }
        val out = StringBuilder()
        ordered.forEachIndexed { i, e ->
            val texts = AT.findAll(e.value).map { unescapeXml(it.groupValues[1]) }.filter { it.isNotBlank() }.toList()
            if (texts.isNotEmpty()) {
                out.append("--- 第 ").append(i + 1).append(" 页 ---\n")
                    .append(texts.joinToString("\n")).append("\n\n")
            }
        }
        return out.toString()
    }

    private fun extractXlsx(context: Context, uri: Uri): String? {
        val files = readZip(context, uri) {
            it == "xl/sharedStrings.xml" || (it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml"))
        }
        val shared = files["xl/sharedStrings.xml"]?.let { xml ->
            SI.findAll(xml).map { si -> T.findAll(si.groupValues[1]).joinToString("") { unescapeXml(it.groupValues[1]) } }.toList()
        } ?: emptyList()
        val sheets = files.entries.filter { it.key.startsWith("xl/worksheets/sheet") }.sortedBy { numIn(it.key) }
        if (sheets.isEmpty()) return null
        val out = StringBuilder()
        sheets.forEachIndexed { idx, e ->
            out.append("--- 工作表 ").append(idx + 1).append(" ---\n")
            for (rowM in ROW.findAll(e.value)) {
                val cells = ArrayList<String>()
                for (cM in CELL.findAll(rowM.value)) {
                    val cell = cM.value
                    val t = T_ATTR.find(cell)?.groupValues?.get(1)
                    when (t) {
                        "s" -> {
                            val si = V.find(cell)?.groupValues?.get(1)?.trim()?.toIntOrNull()
                            cells.add(if (si != null && si in shared.indices) shared[si] else "")
                        }
                        "inlineStr" -> cells.add(T.findAll(cell).joinToString("") { unescapeXml(it.groupValues[1]) })
                        else -> cells.add(unescapeXml(V.find(cell)?.groupValues?.get(1)?.trim().orEmpty()))
                    }
                }
                if (cells.any { it.isNotBlank() }) out.append(cells.joinToString("\t")).append('\n')
            }
            out.append('\n')
        }
        return out.toString()
    }

    private fun numIn(s: String): Int = NUM.find(s.substringAfterLast('/'))?.value?.toIntOrNull() ?: 0

    private fun unescapeXml(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
