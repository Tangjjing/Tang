package com.dschat.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dschat.app.domain.Role
import com.dschat.app.domain.ToolRun
import com.dschat.app.domain.ToolStatus
import com.dschat.app.domain.UiMessage
import com.dschat.app.ui.components.Base64Image
import com.dschat.app.ui.components.MarkdownText
import com.dschat.app.ui.components.StreamingMarkdownText
import java.util.Locale

@Composable
fun MessageBubble(
    message: UiMessage,
    onManageMemory: () -> Unit = {},
    onRegenerate: (Long) -> Unit = {},
    onEdit: (Long) -> Unit = {},
    onSpeak: (String) -> Unit = {}
) {
    when {
        message.tools != null -> ToolGroupCard(message.tools)
        message.role == Role.USER -> UserBubble(message, onEdit)
        else -> AssistantMessage(message, onManageMemory, onRegenerate, onSpeak)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(message: UiMessage, onEdit: (Long) -> Unit = {}) {
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 48.dp),
        horizontalArrangement = Arrangement.End
    ) {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            message.images?.forEach { img ->
                Base64Image(
                    dataUrl = img,
                    modifier = Modifier
                        .sizeIn(maxWidth = 220.dp, maxHeight = 260.dp)
                        .clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Fit
                )
            }
            message.attachmentName?.let { fn ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "📎 $fn",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (message.content.isNotBlank()) {
                Box {
                    // Long-press → 编辑 / 复制. (Replaces the old SelectionContainer; copy is in the menu.)
                    Text(
                        text = message.content,
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .combinedClickable(onClick = {}, onLongClick = { menu = true })
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.5.sp,
                        lineHeight = 20.sp
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; onEdit(message.id) })
                        DropdownMenuItem(text = { Text("复制") }, onClick = { menu = false; clipboard.setText(AnnotatedString(message.content)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantMessage(message: UiMessage, onManageMemory: () -> Unit = {}, onRegenerate: (Long) -> Unit = {}, onSpeak: (String) -> Unit = {}) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // AI avatar on its own line; the reply text flows full-width beneath it (no horizontal waste).
        if (!message.transient) {
            AiAvatar()
            Spacer(Modifier.size(5.dp))
        }
        if (!message.reasoning.isNullOrBlank()) {
            ReasoningSection(
                reasoning = message.reasoning,
                stillThinking = message.isStreaming && message.content.isEmpty(),
                startedAt = message.startedAt,
                thinkMillis = message.thinkMillis
            )
        }
        when {
            message.error -> ErrorBubble(message.content)

            message.content.isEmpty() && message.isStreaming && message.reasoning.isNullOrBlank() ->
                ThinkingIndicator(message.startedAt)

            message.isStreaming -> AiSurface {
                Column {
                    StreamingMarkdownText(
                        content = message.content,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    GenStatusLine(message.startedAt, message.content, message.reasoning)
                }
            }

            else -> AiSurface {
                Column {
                    MarkdownText(markdown = message.content, color = MaterialTheme.colorScheme.onSurface)
                    AssistantActions(message.content, onSpeak = { onSpeak(message.content) }) { onRegenerate(message.id) }
                    GenInfoCaption(message)
                    message.memoryCaptured?.takeIf { it.isNotEmpty() }?.let { MemoryCapturedRow(it, onManageMemory) }
                }
            }
        }
    }
}

/** AI replies are plain full-width text under a small avatar; the gray right-aligned user bubble
 *  provides the visual distinction — calm/objective, Marvis-style. */
@Composable
private fun AiSurface(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 2.dp)) { content() }
}

/** Small circular AI avatar (the Tang "T" mark) shown above each reply. */
@Composable
private fun AiAvatar() {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onBackground),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "T",
            color = MaterialTheme.colorScheme.background,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ---- tool calls: low-key, friendly, collapsed by default ----

private fun friendlyPhrase(names: List<String>): String {
    val n = names.toSet()
    return when {
        n.any { it == "ask_user" } -> "等你选择"
        n.any { it in setOf("web_search", "fetch_url", "http_request") } -> "查询资料"
        n.any { it == "list_files" || it == "find_files" || it.endsWith("_file") } -> "处理本机文件"
        n.any { it in setOf("device_info", "get_location", "read_calendar", "search_contacts", "get_clipboard", "find_app") } -> "查询本机信息"
        n.any { it in setOf("open_app", "open_url", "share_text", "set_clipboard", "set_alarm", "create_calendar_event") } -> "操作手机"
        n.any { it in setOf("save_memory", "read_memory") } -> "整理记忆"
        n.any { it in setOf("run_javascript", "calculator") } -> "计算"
        else -> "处理任务"
    }
}

@Composable
private fun ToolGroupCard(tools: List<ToolRun>) {
    var expanded by remember { mutableStateOf(false) }
    val running = tools.any { it.status == ToolStatus.RUNNING }
    val phrase = friendlyPhrase(tools.map { it.name })
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val runStart = tools.filter { it.status == ToolStatus.RUNNING && it.startedAt > 0L }.minOfOrNull { it.startedAt } ?: 0L
    val elapsedMs = rememberElapsedMs(running = running && runStart > 0L, startAt = if (runStart > 0L) runStart else System.currentTimeMillis())
    val headerText = if (running) "  正在$phrase…" + (if (runStart > 0L) " ${fmtSecs(elapsedMs)}" else "") else "  已$phrase"
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (running) {
                CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp, color = muted)
            } else {
                Icon(Icons.Default.Check, null, Modifier.size(13.dp), tint = muted)
            }
            Text(
                text = headerText,
                fontSize = 12.sp,
                color = muted
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = muted.copy(alpha = 0.6f)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tools.forEach { ToolRunRow(it) }
                }
            }
        }
    }
}

@Composable
private fun ToolRunRow(run: ToolRun) {
    val durText = run.durationMs?.let { String.format(Locale.US, "%.1fs", it / 1000.0) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (run.status) {
                ToolStatus.RUNNING -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                ToolStatus.DONE -> Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                ToolStatus.ERROR -> Icon(Icons.Default.ErrorOutline, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                ToolStatus.DENIED -> Icon(Icons.Default.Block, null, Modifier.size(14.dp), tint = muted)
            }
            Spacer(Modifier.width(6.dp))
            Text(run.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = muted)
            durText?.let { Text("  · $it", fontSize = 11.sp, color = muted) }
        }
        if (run.args.isNotBlank()) {
            Text(
                run.args.replace("\n", " "),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp, top = 2.dp)
            )
        }
        run.result?.takeIf { it.isNotBlank() }?.let {
            Text(
                it.take(800),
                fontSize = 11.sp,
                color = muted,
                lineHeight = 15.sp,
                modifier = Modifier.padding(start = 20.dp, top = 2.dp)
            )
        }
    }
}

@Composable
private fun ReasoningSection(reasoning: String, stillThinking: Boolean, startedAt: Long = 0L, thinkMillis: Long? = null) {
    var expanded by remember { mutableStateOf(false) }
    val liveMs = rememberElapsedMs(running = stillThinking && startedAt > 0L, startAt = if (startedAt > 0L) startedAt else System.currentTimeMillis())
    val header = when {
        stillThinking && startedAt > 0L -> "  深度思考中… ${fmtSecs(liveMs)}"
        stillThinking -> "  深度思考中…"
        thinkMillis != null && thinkMillis > 0 -> "  深度思考 · 用时 ${fmtSecs(thinkMillis)}"
        else -> "  深度思考"
    }
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).animateContentSize()
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = header,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = reasoning,
                    modifier = Modifier.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
                    fontStyle = FontStyle.Italic,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun ThinkingIndicator(startAt: Long = 0L) {
    val ms = rememberElapsedMs(running = startAt > 0L, startAt = if (startAt > 0L) startAt else System.currentTimeMillis())
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // Announce a STABLE phrase to TalkBack once (not the per-0.4s ticking seconds).
        modifier = Modifier
            .padding(vertical = 2.dp)
            .clearAndSetSemantics { liveRegion = LiveRegionMode.Polite; contentDescription = "正在思考" }
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(13.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = if (startAt > 0L) "  思考中… ${fmtSecs(ms)}" else "  思考中…",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.5.sp
        )
    }
}

@Composable
private fun ErrorBubble(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "  $text",
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}

/** Actions under a finished AI reply: 复制（带「已复制 ✓」反馈）+ 重新生成. */
@Composable
private fun AssistantActions(content: String, onSpeak: () -> Unit, onRegenerate: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    val copyTint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(8.dp))
                .clickable { clipboard.setText(AnnotatedString(content)); copied = true }
                .semantics { role = androidx.compose.ui.semantics.Role.Button }
                .padding(horizontal = 4.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                contentDescription = if (copied) "已复制" else "复制",
                modifier = Modifier.size(13.dp),
                tint = copyTint
            )
            Text(if (copied) "  已复制" else "  复制", fontSize = 11.sp, color = copyTint)
        }
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onRegenerate() }
                .semantics { role = androidx.compose.ui.semantics.Role.Button }
                .padding(horizontal = 4.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Refresh, contentDescription = "重新生成", modifier = Modifier.size(13.dp), tint = muted)
            Text("  重新生成", fontSize = 11.sp, color = muted)
        }
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onSpeak() }
                .semantics { role = androidx.compose.ui.semantics.Role.Button }
                .padding(horizontal = 4.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.VolumeUp, contentDescription = "朗读", modifier = Modifier.size(13.dp), tint = muted)
            Text("  朗读", fontSize = 11.sp, color = muted)
        }
    }
}

/** Subtle "🧠 已记住…" line under a reply when auto-memory captured something. Tap → manage memories. */
@Composable
private fun MemoryCapturedRow(titles: List<String>, onClick: () -> Unit) {
    val joined = titles.joinToString("、")
    val label = "🧠 已记住「" + (if (joined.length > 16) joined.take(16) + "…" else joined) + "」"
    Row(
        modifier = Modifier
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---- live "it's working" indicators: elapsed time + token estimate ----

/** Ticks elapsed milliseconds (since [startAt]) every ~0.4s while [running]; freezes when it stops. */
@Composable
private fun rememberElapsedMs(running: Boolean, startAt: Long): Long {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running, startAt) {
        while (running) {
            nowMs = System.currentTimeMillis()
            delay(400)
        }
    }
    return (nowMs - startAt).coerceAtLeast(0)
}

private fun fmtSecs(ms: Long): String = "${((ms + 500) / 1000)}s"

private fun fmtTokens(n: Int): String = String.format(Locale.US, "%,d", n)

/** Rough cross-language token estimate (CJK ≈ 1.6 chars/token, latin ≈ 4 chars/token). Used for a
 *  live "约 N tokens" readout while streaming; the exact count replaces it once the API reports usage. */
private fun estimateTokens(text: String): Int {
    if (text.isEmpty()) return 0
    var cjk = 0
    var other = 0
    for (ch in text) {
        val c = ch.code
        if (c in 0x4E00..0x9FFF || c in 0x3040..0x30FF || c in 0xAC00..0xD7A3) cjk++ else other++
    }
    return (cjk / 1.6f + other / 4f + 0.5f).toInt().coerceAtLeast(1)
}

/** Live status under a streaming reply: "生成中 · 12s · 约 340 tokens". */
@Composable
private fun GenStatusLine(startedAt: Long, content: String, reasoning: String?) {
    if (startedAt <= 0L) return
    val ms = rememberElapsedMs(running = true, startAt = startedAt)
    val tokens = estimateTokens(content) + estimateTokens(reasoning ?: "")
    Text(
        text = "生成中 · ${fmtSecs(ms)} · 约 ${fmtTokens(tokens)} tokens",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(top = 3.dp)
            .clearAndSetSemantics { liveRegion = LiveRegionMode.Polite; contentDescription = "正在生成回复" }
    )
}

/** Caption under a finished reply: "用时 12s · 思考 5s · 输入 1,200 / 输出 340 tokens". */
@Composable
private fun GenInfoCaption(message: UiMessage) {
    val parts = mutableListOf<String>()
    message.genMillis?.let { parts += "用时 ${fmtSecs(it)}" }
    message.thinkMillis?.let { if (it > 0) parts += "思考 ${fmtSecs(it)}" }
    val pt = message.promptTokens
    val ct = message.completionTokens
    when {
        pt != null && ct != null -> parts += "输入 ${fmtTokens(pt)} / 输出 ${fmtTokens(ct)} tokens"
        ct != null -> parts += "输出 ${fmtTokens(ct)} tokens"
    }
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString(" · "),
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 1.dp)
    )
}
