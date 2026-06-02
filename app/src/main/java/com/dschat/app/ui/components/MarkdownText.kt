package com.dschat.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed interface MdBlock {
    data class Code(val lang: String?, val code: String) : MdBlock
    data class Prose(val text: String) : MdBlock
}

private sealed interface MdLine {
    data class Heading(val level: Int, val text: String) : MdLine
    data class Bullet(val text: String) : MdLine
    data class Numbered(val number: String, val text: String) : MdLine
    data class Quote(val text: String) : MdLine
    data class Paragraph(val text: String) : MdLine
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdLine
    data object Divider : MdLine
}

/** Minimal, dependency-free Markdown renderer geared for chat output. */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current
) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val blocks = remember(markdown) { blocksFor(markdown) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Code -> CodeBlock(block.lang, block.code, codeBg)
                is MdBlock.Prose -> ProseBlock(block.text, color, codeBg)
            }
        }
    }
}

@Composable
private fun ProseBlock(text: String, color: Color, codeBg: Color) {
    val items = remember(text) { proseFor(text) }
    val linkColor = MaterialTheme.colorScheme.primary
    val quoteColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            when (item) {
                is MdLine.Heading -> Text(
                    text = inlineAnnotatedCached(item.text, codeBg, linkColor),
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontSize = when (item.level) {
                        1 -> 17.sp
                        2 -> 15.5.sp
                        else -> 14.5.sp
                    },
                    lineHeight = 22.sp
                )

                is MdLine.Bullet -> Row {
                    Text("•  ", color = color, fontSize = 14.sp)
                    Text(inlineAnnotatedCached(item.text, codeBg, linkColor), color = color, fontSize = 14.sp, modifier = Modifier.weight(1f), lineHeight = 19.sp)
                }

                is MdLine.Numbered -> Row {
                    Text("${item.number}.  ", color = color, fontSize = 14.sp)
                    Text(inlineAnnotatedCached(item.text, codeBg, linkColor), color = color, fontSize = 14.sp, modifier = Modifier.weight(1f), lineHeight = 19.sp)
                }

                is MdLine.Quote -> Row {
                    Surface(color = linkColor, modifier = Modifier.size(width = 3.dp, height = 18.dp)) {}
                    Text(
                        inlineAnnotatedCached(item.text, codeBg, linkColor),
                        color = quoteColor,
                        fontStyle = FontStyle.Italic,
                        fontSize = 13.5.sp,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                is MdLine.Table -> TableView(item, color, codeBg, linkColor)

                MdLine.Divider -> HorizontalDivider()

                is MdLine.Paragraph -> Text(
                    text = inlineAnnotatedCached(item.text, codeBg, linkColor),
                    color = color,
                    fontSize = 14.sp,
                    lineHeight = 19.sp
                )
            }
        }
    }
}

@Composable
private fun TableView(table: MdLine.Table, color: Color, codeBg: Color, linkColor: Color) {
    val cols = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
    val border = MaterialTheme.colorScheme.outline

    @Composable
    fun RowLine(cells: List<String>, header: Boolean) {
        Row(modifier = Modifier.fillMaxWidth()) {
            for (c in 0 until cols) {
                Text(
                    text = inlineAnnotatedCached(cells.getOrElse(c) { "" }, codeBg, linkColor),
                    color = color,
                    fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            RowLine(table.header, header = true)
            HorizontalDivider(color = border)
            table.rows.forEachIndexed { idx, row ->
                RowLine(row, header = false)
                if (idx < table.rows.lastIndex) HorizontalDivider(color = border.copy(alpha = 0.4f))
            }
        }
    }
}

@Composable
private fun CodeBlock(lang: String?, code: String, codeBg: Color) {
    val clipboard = LocalClipboardManager.current
    Surface(color = codeBg, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = lang?.ifBlank { "code" } ?: "code",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "复制代码",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            SelectionContainer {
                Text(
                    text = code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }
        }
    }
}

// ---- parsing ----

// Bounded LRU caches so scrolling a message back into view doesn't re-parse its Markdown.
private val blockCache = object : LinkedHashMap<String, List<MdBlock>>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MdBlock>>): Boolean = size > 64
}
private val proseCache = object : LinkedHashMap<String, List<MdLine>>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MdLine>>): Boolean = size > 128
}
private fun blocksFor(md: String): List<MdBlock> = synchronized(blockCache) { blockCache.getOrPut(md) { parseBlocks(md) } }
private fun proseFor(text: String): List<MdLine> = synchronized(proseCache) { proseCache.getOrPut(text) { parseProse(text) } }

// Inline-formatting (bold/italic/code/links) is rebuilt on every (re)composition otherwise — caching
// it keeps scrolling a message into view cheap. Keyed by text + the two theme colors used.
private val inlineCache = object : LinkedHashMap<String, AnnotatedString>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnnotatedString>): Boolean = size > 256
}
private fun inlineAnnotatedCached(text: String, codeBg: Color, linkColor: Color): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")
    val key = "${codeBg.value}|${linkColor.value}|$text"
    return synchronized(inlineCache) { inlineCache.getOrPut(key) { inlineAnnotated(text, codeBg, linkColor) } }
}

private fun parseBlocks(md: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = md.split("\n")
    val prose = StringBuilder()
    fun flushProse() {
        if (prose.isNotBlank()) blocks += MdBlock.Prose(prose.toString().trim('\n'))
        prose.clear()
    }
    var i = 0
    while (i < lines.size) {
        val trimmed = lines[i].trimStart()
        if (trimmed.startsWith("```")) {
            flushProse()
            val lang = trimmed.removePrefix("```").trim().ifEmpty { null }
            val code = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                code.append(lines[i]).append("\n")
                i++
            }
            i++ // skip closing fence (if present)
            blocks += MdBlock.Code(lang, code.toString().trimEnd('\n'))
        } else {
            prose.append(lines[i]).append("\n")
            i++
        }
    }
    flushProse()
    return blocks
}

private val numberedRegex = Regex("^(\\d+)\\.\\s+(.*)")
private val tableSepRegex = Regex("^\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?$")

private fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    return t.contains("|") && t.contains("-") && tableSepRegex.matches(t)
}

private fun splitRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|")) s = s.substring(0, s.length - 1)
    return s.split("|").map { it.trim() }
}

private fun parseProse(text: String): List<MdLine> {
    val out = mutableListOf<MdLine>()
    val lines = text.split("\n")
    val para = StringBuilder()
    fun flush() {
        if (para.isNotEmpty()) {
            out += MdLine.Paragraph(para.toString())
            para.clear()
        }
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trimEnd()
        val t = line.trimStart()
        // GFM table: a row containing '|' immediately followed by a separator row
        if (t.contains("|") && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
            flush()
            val header = splitRow(t)
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].isNotBlank() && lines[i].contains("|")) {
                rows += splitRow(lines[i])
                i++
            }
            out += MdLine.Table(header, rows)
            continue
        }
        when {
            t.isEmpty() -> flush()
            t.startsWith("### ") -> { flush(); out += MdLine.Heading(3, t.removePrefix("### ")) }
            t.startsWith("## ") -> { flush(); out += MdLine.Heading(2, t.removePrefix("## ")) }
            t.startsWith("# ") -> { flush(); out += MdLine.Heading(1, t.removePrefix("# ")) }
            t == "---" || t == "***" || t == "___" -> { flush(); out += MdLine.Divider }
            t.startsWith("> ") -> { flush(); out += MdLine.Quote(t.removePrefix("> ")) }
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") -> { flush(); out += MdLine.Bullet(t.drop(2)) }
            numberedRegex.matches(t) -> {
                flush()
                val m = numberedRegex.find(t)!!
                out += MdLine.Numbered(m.groupValues[1], m.groupValues[2])
            }
            else -> {
                if (para.isNotEmpty()) para.append("\n")
                para.append(line)
            }
        }
        i++
    }
    flush()
    return out
}

// Inline formatting: inline code, bold, italic, and [text](url) links.
private fun inlineAnnotated(text: String, codeBg: Color, linkColor: Color): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(c); i++
                    }
                }

                c == '*' && i + 1 < text.length && text[i + 1] == '*' -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(c); i++
                    }
                }

                c == '*' || c == '_' -> {
                    val end = text.indexOf(c, i + 1)
                    if (end > i) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(c); i++
                    }
                }

                c == '[' -> {
                    val close = text.indexOf(']', i + 1)
                    if (close > i && close + 1 < text.length && text[close + 1] == '(') {
                        val urlEnd = text.indexOf(')', close + 2)
                        if (urlEnd > close) {
                            val label = text.substring(i + 1, close)
                            val url = text.substring(close + 2, urlEnd)
                            withLink(
                                LinkAnnotation.Url(
                                    url,
                                    TextLinkStyles(
                                        SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                                    )
                                )
                            ) { append(label) }
                            i = urlEnd + 1
                        } else {
                            append(c); i++
                        }
                    } else {
                        append(c); i++
                    }
                }

                else -> {
                    append(c); i++
                }
            }
        }
    }
