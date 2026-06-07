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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
import kotlinx.coroutines.delay

private sealed interface MdBlock {
    data class Code(val lang: String?, val code: String) : MdBlock
    data class Prose(val text: String) : MdBlock
}

private sealed interface MdLine {
    data class Heading(val level: Int, val text: String) : MdLine
    data class Bullet(val text: String, val indent: Int) : MdLine
    data class Numbered(val number: String, val text: String, val indent: Int) : MdLine
    data class Task(val checked: Boolean, val text: String, val indent: Int) : MdLine
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

/**
 * Markdown for a reply that is still streaming. Renders the *settled* prefix as full Markdown
 * (cached, so it only re-parses when a new block completes) and the in-flight tail as a small,
 * cheap fragment with a caret — so formatting appears live without the O(n²) cost of re-parsing
 * the whole message on every token. A short sampling interval coalesces token bursts.
 */
@Composable
fun StreamingMarkdownText(
    content: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current
) {
    val latest = rememberUpdatedState(content)
    var shown by remember { mutableStateOf(content) }
    LaunchedEffect(Unit) {
        while (true) {
            if (shown != latest.value) shown = latest.value
            delay(STREAM_SAMPLE_MS)
        }
    }
    val (stable, tail) = remember(shown) { splitStreaming(shown) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (stable.isNotEmpty()) MarkdownText(stable, color = color)
        val tailWithCaret = if (tail.isBlank()) "▌" else tail.trimEnd() + " ▌"
        MarkdownText(tailWithCaret, color = color)
    }
}

/** Split streaming text into (settled, in-flight). The tail is the open code block (if a fence is
 *  still open) or the current paragraph; everything before it is stable and won't change. */
private fun splitStreaming(content: String): Pair<String, String> {
    if (content.isEmpty()) return "" to ""
    val fenceCount = content.split("```").size - 1
    if (fenceCount % 2 == 1) {
        val idx = content.lastIndexOf("```")
        return content.substring(0, idx).trimEnd('\n') to content.substring(idx)
    }
    val brk = content.lastIndexOf("\n\n")
    return if (brk < 0) "" to content else content.substring(0, brk) to content.substring(brk + 2)
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
                        1 -> 18.sp
                        2 -> 16.sp
                        3 -> 15.sp
                        else -> 14.sp
                    },
                    lineHeight = 23.sp
                )

                is MdLine.Bullet -> Row(Modifier.padding(start = (item.indent * 18).dp)) {
                    Text(bulletGlyph(item.indent), color = color.copy(alpha = 0.7f), fontSize = 14.sp)
                    Text(inlineAnnotatedCached(item.text, codeBg, linkColor), color = color, fontSize = 14.sp, modifier = Modifier.weight(1f), lineHeight = 19.sp)
                }

                is MdLine.Numbered -> Row(Modifier.padding(start = (item.indent * 18).dp)) {
                    Text("${item.number}.  ", color = color.copy(alpha = 0.7f), fontSize = 14.sp)
                    Text(inlineAnnotatedCached(item.text, codeBg, linkColor), color = color, fontSize = 14.sp, modifier = Modifier.weight(1f), lineHeight = 19.sp)
                }

                is MdLine.Task -> Row(
                    Modifier.padding(start = (item.indent * 18).dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (item.checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                        contentDescription = if (item.checked) "已完成" else "未完成",
                        tint = if (item.checked) MaterialTheme.colorScheme.primary else color.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp).padding(end = 6.dp)
                    )
                    Text(
                        inlineAnnotatedCached(item.text, codeBg, linkColor),
                        color = if (item.checked) color.copy(alpha = 0.6f) else color,
                        textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                        fontSize = 14.sp, modifier = Modifier.weight(1f), lineHeight = 19.sp
                    )
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

private fun bulletGlyph(indent: Int): String = when (indent % 3) {
    0 -> "•  "
    1 -> "◦  "
    else -> "▪  "
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
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    val theme = codeThemeFor(codeBg, MaterialTheme.colorScheme.onSurface)
    val highlighted = remember(code, lang, theme) { highlightCached(code, lang, theme) }
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
                IconButton(onClick = { clipboard.setText(AnnotatedString(code)); copied = true }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = if (copied) "已复制" else "复制代码",
                        modifier = Modifier.size(16.dp),
                        tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            SelectionContainer {
                Text(
                    text = highlighted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = theme.default,
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

private val numberedRegex = Regex("^(\\d+)[.)]\\s+(.*)")
private val taskRegex = Regex("^[-*+]\\s+\\[([ xX])]\\s+(.*)")
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

/** Indent level from leading whitespace (2 spaces or 1 tab per level), capped. */
private fun indentLevel(line: String): Int {
    var spaces = 0
    for (ch in line) {
        when (ch) {
            ' ' -> spaces += 1
            '\t' -> spaces += 2
            else -> return (spaces / 2).coerceAtMost(4)
        }
    }
    return 0
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
        val indent = indentLevel(line)
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
            t.startsWith("###### ") -> { flush(); out += MdLine.Heading(6, t.removePrefix("###### ")) }
            t.startsWith("##### ") -> { flush(); out += MdLine.Heading(5, t.removePrefix("##### ")) }
            t.startsWith("#### ") -> { flush(); out += MdLine.Heading(4, t.removePrefix("#### ")) }
            t.startsWith("### ") -> { flush(); out += MdLine.Heading(3, t.removePrefix("### ")) }
            t.startsWith("## ") -> { flush(); out += MdLine.Heading(2, t.removePrefix("## ")) }
            t.startsWith("# ") -> { flush(); out += MdLine.Heading(1, t.removePrefix("# ")) }
            t == "---" || t == "***" || t == "___" -> { flush(); out += MdLine.Divider }
            t.startsWith("> ") -> { flush(); out += MdLine.Quote(t.removePrefix("> ")) }
            taskRegex.matches(t) -> {
                flush()
                val m = taskRegex.find(t)!!
                out += MdLine.Task(m.groupValues[1].lowercase() == "x", m.groupValues[2], indent)
            }
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") -> { flush(); out += MdLine.Bullet(t.drop(2), indent) }
            numberedRegex.matches(t) -> {
                flush()
                val m = numberedRegex.find(t)!!
                out += MdLine.Numbered(m.groupValues[1], m.groupValues[2], indent)
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

// Inline formatting: code, bold, italic, bold-italic, strikethrough, links, images, escapes.
private fun inlineAnnotated(text: String, codeBg: Color, linkColor: Color): AnnotatedString =
    buildAnnotatedString {
        val n = text.length
        var i = 0
        fun isWordChar(idx: Int): Boolean = idx in 0 until n && (text[idx].isLetterOrDigit())
        // Parse a [label](url); returns index after ')' or -1. openBracket points at '['.
        fun tryLink(openBracket: Int, image: Boolean): Int {
            val close = text.indexOf(']', openBracket + 1)
            if (close <= openBracket || close + 1 >= n || text[close + 1] != '(') return -1
            val urlEnd = text.indexOf(')', close + 2)
            if (urlEnd <= close) return -1
            val label = text.substring(openBracket + 1, close)
            val url = text.substring(close + 2, urlEnd)
            if (image) {
                // Inline images are rare in chat answers; show the alt text as a link to the source.
                withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
                    append(label.ifBlank { "图片" })
                }
            } else {
                withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
                    append(label)
                }
            }
            return urlEnd + 1
        }
        while (i < n) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < n -> { append(text[i + 1]); i += 2 }

                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else { append(c); i++ }
                }

                c == '!' && i + 1 < n && text[i + 1] == '[' -> {
                    val next = tryLink(i + 1, image = true)
                    if (next > 0) i = next else { append(c); i++ }
                }

                c == '[' -> {
                    val next = tryLink(i, image = false)
                    if (next > 0) i = next else { append(c); i++ }
                }

                c == '*' && i + 2 < n && text[i + 1] == '*' && text[i + 2] == '*' -> {
                    val end = text.indexOf("***", i + 3)
                    if (end > i) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) { append(text.substring(i + 3, end)) }
                        i = end + 3
                    } else { append(c); i++ }
                }

                c == '*' && i + 1 < n && text[i + 1] == '*' -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                        i = end + 2
                    } else { append(c); i++ }
                }

                c == '_' && i + 1 < n && text[i + 1] == '_' -> {
                    val end = text.indexOf("__", i + 2)
                    if (end > i && !isWordChar(i - 1) && !isWordChar(end + 2)) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                        i = end + 2
                    } else { append(c); i++ }
                }

                c == '~' && i + 1 < n && text[i + 1] == '~' -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end > i) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(text.substring(i + 2, end)) }
                        i = end + 2
                    } else { append(c); i++ }
                }

                c == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                        i = end + 1
                    } else { append(c); i++ }
                }

                // `_` is italic only at word boundaries, so identifiers like my_var_name stay literal.
                c == '_' && !isWordChar(i - 1) -> {
                    val end = text.indexOf('_', i + 1)
                    if (end > i && !isWordChar(end + 1)) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                        i = end + 1
                    } else { append(c); i++ }
                }

                else -> { append(c); i++ }
            }
        }
    }

// ---- code syntax highlighting (dependency-free) ----

private data class CodeTheme(val keyword: Color, val string: Color, val comment: Color, val number: Color, val default: Color)

/** A curated palette that adapts to the code surface's lightness (so it reads in light & dark). */
private fun codeThemeFor(codeBg: Color, default: Color): CodeTheme {
    val dark = codeBg.luminance() < 0.5f
    return if (dark) CodeTheme(
        keyword = Color(0xFFC792EA), string = Color(0xFFC3E88D),
        comment = Color(0xFF8A94A6), number = Color(0xFFF78C6C), default = default
    ) else CodeTheme(
        keyword = Color(0xFF8E24AA), string = Color(0xFF2E7D32),
        comment = Color(0xFF9E9E9E), number = Color(0xFFD84315), default = default
    )
}

private val codeCache = object : LinkedHashMap<String, AnnotatedString>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnnotatedString>): Boolean = size > 48
}
private fun highlightCached(code: String, lang: String?, theme: CodeTheme): AnnotatedString {
    if (code.isEmpty()) return AnnotatedString("")
    val key = "${theme.keyword.value}|${lang ?: ""}|$code"
    return synchronized(codeCache) { codeCache.getOrPut(key) { highlight(code, lang, theme) } }
}

private data class LangSpec(val lineComments: List<String>, val block: Boolean, val keywords: Set<String>, val caseInsensitive: Boolean)

private fun specFor(lang: String?): LangSpec {
    val l = lang?.lowercase()?.trim().orEmpty()
    return when {
        l in setOf("py", "python") -> LangSpec(listOf("#"), false, KW_PYTHON, false)
        l in setOf("sql", "mysql", "postgres", "postgresql", "sqlite") -> LangSpec(listOf("--"), true, KW_SQL, true)
        l in setOf("sh", "bash", "shell", "zsh", "console", "fish") -> LangSpec(listOf("#"), false, KW_SHELL, false)
        l in setOf("json", "jsonc") -> LangSpec(listOf("//"), false, KW_LITERAL, false)
        l in setOf("yaml", "yml", "toml", "ini", "properties") -> LangSpec(listOf("#"), false, KW_LITERAL, false)
        l in setOf("kt", "kotlin", "java", "js", "javascript", "jsx", "ts", "typescript", "tsx",
            "c", "h", "cpp", "c++", "cc", "cs", "csharp", "go", "golang", "rust", "rs", "swift",
            "scala", "dart", "php", "groovy") -> LangSpec(listOf("//"), true, KW_CLIKE, false)
        else -> LangSpec(listOf("//", "#"), true, KW_LITERAL, false)
    }
}

private fun highlight(code: String, lang: String?, theme: CodeTheme): AnnotatedString {
    val spec = specFor(lang)
    val n = code.length
    return buildAnnotatedString {
        var i = 0
        while (i < n) {
            val c = code[i]
            // Triple-quoted strings (Kotlin/Python raw/multiline)
            if ((c == '"' || c == '\'') && i + 2 < n && code[i + 1] == c && code[i + 2] == c) {
                val close = code.indexOf("$c$c$c", i + 3)
                val end = if (close < 0) n else close + 3
                withStyle(SpanStyle(color = theme.string)) { append(code.substring(i, end)) }
                i = end; continue
            }
            // Line comments
            var matchedComment = false
            for (tok in spec.lineComments) {
                if (code.startsWith(tok, i)) {
                    val nl = code.indexOf('\n', i).let { if (it < 0) n else it }
                    withStyle(SpanStyle(color = theme.comment, fontStyle = FontStyle.Italic)) { append(code.substring(i, nl)) }
                    i = nl; matchedComment = true; break
                }
            }
            if (matchedComment) continue
            // Block comments
            if (spec.block && c == '/' && i + 1 < n && code[i + 1] == '*') {
                val close = code.indexOf("*/", i + 2)
                val end = if (close < 0) n else close + 2
                withStyle(SpanStyle(color = theme.comment, fontStyle = FontStyle.Italic)) { append(code.substring(i, end)) }
                i = end; continue
            }
            // Strings
            if (c == '"' || c == '\'' || c == '`') {
                var j = i + 1
                while (j < n) {
                    if (code[j] == '\\') { j += 2; continue }
                    if (code[j] == c) { j++; break }
                    if (code[j] == '\n' && c != '`') break
                    j++
                }
                withStyle(SpanStyle(color = theme.string)) { append(code.substring(i, j.coerceAtMost(n))) }
                i = j.coerceAtMost(n); continue
            }
            // Numbers
            if (c.isDigit() || (c == '.' && i + 1 < n && code[i + 1].isDigit())) {
                var j = i
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
                withStyle(SpanStyle(color = theme.number)) { append(code.substring(i, j)) }
                i = j; continue
            }
            // Identifiers / keywords
            if (c.isLetter() || c == '_' || c == '@' || c == '$') {
                var j = i + 1
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                val word = code.substring(i, j)
                val probe = if (spec.caseInsensitive) word.lowercase() else word
                if (spec.keywords.contains(probe)) {
                    withStyle(SpanStyle(color = theme.keyword, fontWeight = FontWeight.Medium)) { append(word) }
                } else append(word)
                i = j; continue
            }
            append(c); i++
        }
    }
}

private val KW_LITERAL = setOf("true", "false", "null", "nil", "none", "undefined", "nan")
private val KW_CLIKE = setOf(
    "fun", "val", "var", "let", "const", "class", "object", "interface", "enum", "struct", "trait",
    "impl", "data", "sealed", "abstract", "open", "final", "override", "public", "private", "protected",
    "internal", "static", "package", "import", "namespace", "using", "module", "export", "default",
    "return", "if", "else", "elseif", "when", "switch", "case", "for", "while", "do", "break", "continue",
    "try", "catch", "finally", "throw", "throws", "new", "delete", "this", "self", "super", "extends",
    "implements", "void", "int", "long", "short", "float", "double", "boolean", "bool", "char", "byte",
    "string", "function", "async", "await", "yield", "suspend", "lateinit", "companion", "init", "by",
    "in", "is", "as", "out", "typealias", "operator", "vararg", "inline", "reified", "where", "func",
    "type", "def", "go", "defer", "chan", "select", "map", "mut", "fn", "pub", "use", "match", "unsafe",
    "true", "false", "null", "nil", "none", "undefined"
)
private val KW_PYTHON = setOf(
    "def", "class", "return", "if", "elif", "else", "for", "while", "import", "from", "as", "try",
    "except", "finally", "raise", "with", "lambda", "yield", "global", "nonlocal", "pass", "break",
    "continue", "and", "or", "not", "in", "is", "del", "assert", "async", "await", "True", "False",
    "None", "self", "print"
)
private val KW_SQL = setOf(
    "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create", "table",
    "drop", "alter", "add", "column", "index", "view", "join", "inner", "left", "right", "outer", "full",
    "on", "group", "by", "order", "having", "limit", "offset", "as", "and", "or", "not", "null", "is",
    "distinct", "count", "sum", "avg", "min", "max", "case", "when", "then", "else", "end", "union",
    "all", "exists", "between", "like", "in", "desc", "asc", "primary", "key", "foreign", "references",
    "default", "unique", "constraint", "with"
)
private val KW_SHELL = setOf(
    "if", "then", "fi", "else", "elif", "for", "in", "do", "done", "while", "until", "case", "esac",
    "function", "return", "export", "local", "readonly", "declare", "echo", "cd", "exit", "set", "unset",
    "source", "alias", "true", "false"
)

private const val STREAM_SAMPLE_MS = 80L
