@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.dschat.app.ui.memory

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dschat.app.domain.MemoryItem
import com.dschat.app.ui.settings.ToggleRow

@Composable
fun MemoryListScreen(
    viewModel: MemoryViewModel,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onAdd: () -> Unit
) {
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val autoMem by viewModel.autoMemoryEnabled.collectAsStateWithLifecycle()
    val review by viewModel.autoMemoryReview.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf<String?>(null) } // null = 全部
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }

    val pending = memories.filter { it.pending }
    val confirmed = memories.filter { !it.pending }
    val categories = confirmed.map { it.category.trim().ifBlank { "其它" } }.distinct().sorted()
    val visible = confirmed.filter { m ->
        val q = query.trim()
        val matchesQuery = q.isBlank() || m.title.contains(q, ignoreCase = true) || m.content.contains(q, ignoreCase = true)
        val cat = m.category.trim().ifBlank { "其它" }
        val matchesCat = categoryFilter == null || cat == categoryFilter
        matchesQuery && matchesCat
    }

    fun exitSelection() { selecting = false; selected = emptySet() }
    fun toggleSelect(id: Long) {
        selected = if (id in selected) selected - id else selected + id
        if (selected.isEmpty()) selecting = false
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(if (selecting) "已选 ${selected.size} 条" else "记忆") },
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = { exitSelection() }) { Icon(Icons.Default.Close, "取消多选") }
                    } else {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                    }
                },
                actions = {
                    if (selecting && selected.isNotEmpty()) {
                        TextButton(onClick = { viewModel.setEnabledBatch(selected, true); exitSelection() }) { Text("启用") }
                        TextButton(onClick = { viewModel.setEnabledBatch(selected, false); exitSelection() }) { Text("停用") }
                        IconButton(onClick = { viewModel.deleteBatch(selected); exitSelection() }) {
                            Icon(Icons.Default.Delete, "批量删除")
                        }
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            if (!selecting) FloatingActionButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "新建记忆") }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    "记忆里启用的条目会在对话时按相关性自动告诉模型，让它“记住”你的信息与偏好。可建多条、随时开关、长按可多选。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
                ToggleRow(
                    "自动记住关于我的信息",
                    "对话结束后自动判断并记下关于你的长期事实（偏好/身份/环境等）。自动记的会标「自动」，可随时停用或删除。",
                    autoMem
                ) { viewModel.setAutoMemory(it) }
                ToggleRow(
                    "新记忆先确认再生效",
                    "开启后，自动记下的内容会进入下面的「待确认」，由你确认后才会用于对话；关闭则直接生效（默认）。",
                    review
                ) { viewModel.setAutoMemoryReview(it) }
            }

            // Search
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索记忆…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Category filter chips
            if (categories.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = categoryFilter == null,
                        onClick = { categoryFilter = null },
                        label = { Text("全部") }
                    )
                    categories.forEach { cat ->
                        FilterChip(
                            selected = categoryFilter == cat,
                            onClick = { categoryFilter = if (categoryFilter == cat) null else cat },
                            label = { Text(cat) }
                        )
                    }
                }
            }

            if (memories.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Memory,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "还没有记忆，点 + 新建一条",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (pending.isNotEmpty()) {
                        item(key = "pending-header") {
                            Text(
                                "待确认（${pending.size}）",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp)
                            )
                        }
                        items(pending, key = { "p-${it.id}" }) { m ->
                            PendingCard(
                                item = m,
                                onConfirm = { viewModel.confirm(m.id) },
                                onReject = { viewModel.reject(m.id) }
                            )
                        }
                        item(key = "confirmed-header") {
                            Text(
                                "记忆",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)
                            )
                        }
                    }
                    items(visible, key = { it.id }) { m ->
                        MemoryCard(
                            item = m,
                            selecting = selecting,
                            checked = m.id in selected,
                            onClick = {
                                if (selecting) toggleSelect(m.id) else onEdit(m.id)
                            },
                            onLongClick = {
                                selecting = true; toggleSelect(m.id)
                            },
                            onToggle = { viewModel.setEnabled(m.id, it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingCard(item: MemoryItem, onConfirm: () -> Unit, onReject: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            CategoryAutoBadges(item)
            Text(
                item.content.ifBlank { "（空）" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onReject) { Text("忽略") }
                TextButton(onClick = onConfirm) { Text("确认") }
            }
        }
    }
}

@Composable
private fun MemoryCard(
    item: MemoryItem,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    Surface(
        color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.pinned) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = "已置顶",
                            modifier = Modifier.size(14.dp).padding(end = 4.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        item.title.ifBlank { "（未命名记忆）" },
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    CategoryAutoBadges(item)
                }
                Text(
                    item.content.ifBlank { "（空）" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (item.sourceConversationId != 0L) {
                    Text(
                        "来自一次对话",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (selecting) {
                Checkbox(checked = checked, onCheckedChange = { onClick() })
            } else {
                Switch(checked = item.enabled, onCheckedChange = onToggle)
            }
        }
    }
}

/** Inline [类别] + 「自动」 badges, reused by the pending and normal cards. */
@Composable
private fun CategoryAutoBadges(item: MemoryItem) {
    if (item.category.isNotBlank()) {
        Spacer(Modifier.width(6.dp))
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp)) {
            Text(
                item.category,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
    }
    if (item.auto) {
        Spacer(Modifier.width(6.dp))
        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(6.dp)) {
            Text(
                "自动",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
    }
}

@Composable
fun MemoryEditScreen(
    viewModel: MemoryViewModel,
    memoryId: Long,
    onBack: () -> Unit
) {
    val existing = remember(memoryId) { if (memoryId >= 0) viewModel.get(memoryId) else null }
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var content by remember { mutableStateOf(existing?.content ?: "") }
    var category by remember { mutableStateOf(existing?.category ?: "") }
    var pinned by remember { mutableStateOf(existing?.pinned ?: false) }
    val enabled = existing?.enabled ?: true

    fun save() {
        if (title.isBlank() && content.isBlank()) { onBack(); return }
        if (existing != null) {
            viewModel.update(existing.copy(title = title, content = content, category = category, pinned = pinned))
        } else {
            val id = viewModel.add(title, content, category)
            if (pinned) viewModel.setPinned(id, true)
        }
        onBack()
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(if (existing != null) "编辑记忆" else "新建记忆") },
                navigationIcon = {
                    IconButton(onClick = { save() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回并保存") }
                },
                actions = {
                    if (existing != null) {
                        IconButton(onClick = { viewModel.delete(existing.id); onBack() }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除")
                        }
                    }
                    IconButton(onClick = { save() }) { Icon(Icons.Default.Check, contentDescription = "保存") }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("标题") },
                placeholder = { Text("例如：关于我 / 写代码偏好") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = category,
                onValueChange = { category = it },
                label = { Text("类别（可空）") },
                placeholder = { Text("个人信息 / 编码偏好 / 饮食 / 环境设备 …") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("内容") },
                placeholder = { Text("写下希望模型长期记住的信息，例如：我叫小明，是一名 Android 开发者，喜欢简洁直接的回答。") },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("置顶（始终注入）", fontWeight = FontWeight.Medium)
                    Text(
                        "开启后这条记忆每次对话都会带上，不参与相关性筛选，也不会被自动清理。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = pinned, onCheckedChange = { pinned = it })
            }
            Text(
                if (enabled) "状态：已启用（会注入对话）" else "状态：已停用",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
