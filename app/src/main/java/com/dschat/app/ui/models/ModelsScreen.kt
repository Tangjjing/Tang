@file:OptIn(ExperimentalMaterial3Api::class)

package com.dschat.app.ui.models

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dschat.app.domain.ChatModel

@Composable
fun ModelsScreen(viewModel: ModelsViewModel, onBack: () -> Unit) {
    val models by viewModel.models.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedId.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()

    // editing == null -> not editing; editing == placeholder with blank id -> adding
    var editing by remember { mutableStateOf<ChatModel?>(null) }
    var adding by remember { mutableStateOf(false) }
    var addingProvider by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = { viewModel.fetchFromApi() }) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "从接口拉取模型")
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) {
                Icon(Icons.Default.Add, contentDescription = "添加模型")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (status.fetching) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            status.message?.let { msg ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(msg, color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.clearStatus() }) { Text("知道了") }
                    }
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clickable { addingProvider = true }
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("添加供应商", fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp)
                        Text("填接口地址 + Key，自动拉取它支持的全部模型", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val grouped = remember(models) { models.groupBy { it.provider.ifBlank { "其它" } } }
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                grouped.forEach { (provider, ms) ->
                    item(key = "hdr_$provider") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 10.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                provider,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { viewModel.refreshProvider(provider) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.CloudDownload, "刷新该供应商模型", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    items(ms, key = { it.id }) { m ->
                        ModelRow(
                            model = m,
                            selected = m.id == selectedId,
                            canDelete = models.size > 1,
                            onSelect = { viewModel.select(m.id) },
                            onEdit = { editing = m },
                            onDelete = { viewModel.delete(m.id) }
                        )
                    }
                }
            }
        }
    }

    if (adding) {
        ModelEditDialog(
            initial = null,
            onDismiss = { adding = false },
            onSave = { id, name, reasoning, vision, provider, baseUrl, apiKey -> viewModel.upsert(id, name, reasoning, vision, provider, baseUrl, apiKey); adding = false }
        )
    }
    editing?.let { m ->
        ModelEditDialog(
            initial = m,
            onDismiss = { editing = null },
            onSave = { id, name, reasoning, vision, provider, baseUrl, apiKey -> viewModel.upsert(id, name, reasoning, vision, provider, baseUrl, apiKey); editing = null }
        )
    }
    if (addingProvider) {
        AddProviderDialog(
            onDismiss = { addingProvider = false },
            onConfirm = { name, url, key -> viewModel.addProvider(name, url, key); addingProvider = false }
        )
    }
}

/** "本应用不含额度，需自备 Key" note + tappable links to each provider's API-key console. */
@Composable
private fun KeyHelpLinks() {
    val context = LocalContext.current
    fun open(url: String) = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "本应用不含模型额度，需自备各家 API Key（一般按用量付费）。没有 Key？点下面去申请：",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            listOf(
                "DeepSeek" to "https://platform.deepseek.com/api_keys",
                "Kimi" to "https://platform.moonshot.cn/console/api-keys",
                "智谱" to "https://open.bigmodel.cn/usercenter/apikeys",
                "OpenRouter" to "https://openrouter.ai/keys"
            ).forEach { (label, url) ->
                Text(
                    label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { open(url) }
                )
            }
        }
    }
}

@Composable
private fun AddProviderDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, baseUrl: String, apiKey: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加供应商") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称（如 我的中转 / Kimi）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it }, label = { Text("接口地址 baseURL") }, placeholder = { Text("https://xxx.com/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(key, { key = it }, label = { Text("API Key") }, placeholder = { Text("sk-...") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(
                    "保存后自动请求该地址的 /models，拉取它支持的所有模型并按供应商分组。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                KeyHelpLinks()
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, url, key) }, enabled = url.isNotBlank() && key.isNotBlank()) { Text("拉取并添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ModelRow(
    model: ChatModel,
    selected: Boolean,
    canDelete: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable { onSelect() }
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(model.displayName, fontWeight = FontWeight.SemiBold)
                    if (model.reasoning) {
                        Icon(
                            Icons.Default.Psychology,
                            contentDescription = "推理",
                            modifier = Modifier.padding(start = 6.dp).size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(model.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(20.dp)) }
            if (canDelete) {
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(20.dp)) }
            }
        }
    }
}

@Composable
private fun ModelEditDialog(
    initial: ChatModel?,
    onDismiss: () -> Unit,
    onSave: (id: String, name: String, reasoning: Boolean, vision: Boolean, provider: String, baseUrl: String, apiKey: String) -> Unit
) {
    var id by remember { mutableStateOf(initial?.id ?: "") }
    var name by remember { mutableStateOf(initial?.displayName ?: "") }
    var reasoning by remember { mutableStateOf(initial?.reasoning ?: false) }
    var vision by remember { mutableStateOf(initial?.vision ?: false) }
    var provider by remember { mutableStateOf(initial?.provider ?: "") }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var apiKey by remember { mutableStateOf(initial?.apiKey ?: "") }
    val isEdit = initial != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "编辑模型" else "添加模型") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("模型 ID（API 用）") },
                    placeholder = { Text("deepseek-v4-flash") },
                    singleLine = true,
                    enabled = !isEdit,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("显示名（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = provider,
                    onValueChange = { provider = it },
                    label = { Text("供应商（分组用，可留空自动推断）") },
                    placeholder = { Text("DeepSeek / Kimi / 我的中转") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("接口地址（留空=用全局）") },
                    placeholder = { Text("https://api.moonshot.cn/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("该模型 API Key（留空=用全局）") },
                    placeholder = { Text("sk-...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "非 DeepSeek 模型（Kimi/通义/智谱/Claude 等）需各自的接口地址与 key；Claude 走 OpenRouter 等 OpenAI 兼容网关。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                KeyHelpLinks()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("标记为推理模型（显示思维链）", modifier = Modifier.weight(1f), fontSize = 14.sp)
                    Switch(checked = reasoning, onCheckedChange = { reasoning = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("支持识图（直接把图片发给它）；关闭则本机 OCR 转文字", modifier = Modifier.weight(1f), fontSize = 14.sp)
                    Switch(checked = vision, onCheckedChange = { vision = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(id, name, reasoning, vision, provider, baseUrl, apiKey) }, enabled = id.isNotBlank()) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
