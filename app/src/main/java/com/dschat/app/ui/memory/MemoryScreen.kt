@file:OptIn(ExperimentalMaterial3Api::class)

package com.dschat.app.ui.memory

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("记忆") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "新建记忆") }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "记忆里启用的条目会在每次对话时自动告诉模型，让它“记住”你的信息与偏好。可建多条、随时开关。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
            Column(Modifier.padding(horizontal = 16.dp, vertical = 0.dp)) {
                ToggleRow(
                    "自动记住关于我的信息",
                    "对话结束后自动判断并记下关于你的长期事实（偏好/身份/环境等）。自动记的会标「自动」，可随时停用或删除。",
                    autoMem
                ) { viewModel.setAutoMemory(it) }
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
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(memories, key = { it.id }) { m ->
                        MemoryCard(
                            item = m,
                            onClick = { onEdit(m.id) },
                            onToggle = { viewModel.setEnabled(m.id, it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryCard(item: MemoryItem, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.title.ifBlank { "（未命名记忆）" },
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (item.category.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
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
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                "自动",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Text(
                    item.content.ifBlank { "（空）" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Switch(checked = item.enabled, onCheckedChange = onToggle)
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
    val enabled = existing?.enabled ?: true

    fun save() {
        if (title.isBlank() && content.isBlank()) { onBack(); return }
        if (existing != null) {
            viewModel.update(existing.copy(title = title, content = content, category = category))
        } else {
            viewModel.add(title, content, category)
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
                }
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
            Text(
                if (enabled) "状态：已启用（会注入对话）" else "状态：已停用",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
