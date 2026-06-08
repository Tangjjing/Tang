@file:OptIn(ExperimentalMaterial3Api::class)

package com.dschat.app.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dschat.app.BuildConfig
import com.dschat.app.LaunchAction
import com.dschat.app.agent.ExecutionMode
import com.dschat.app.data.local.ConversationEntity
import com.dschat.app.domain.ChatModel
import com.dschat.app.domain.Role
import com.dschat.app.ui.components.Base64Image
import com.dschat.app.ui.components.QrScannerScreen
import com.dschat.app.util.DocumentTextExtractor
import com.dschat.app.util.Tts
import com.dschat.app.ui.theme.BrandMark
import com.dschat.app.ui.theme.BrandWordmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import java.io.ByteArrayOutputStream
import java.io.File

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenMemory: () -> Unit = {},
    launchAction: LaunchAction? = null,
    onActionConsumed: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current
    var showPromptDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val dataUrl = withContext(Dispatchers.IO) { encodeImage(context, uri) }
            if (dataUrl != null) viewModel.attachImage(dataUrl) else snackbarHostState.showSnackbar("图片读取失败")
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val res = withContext(Dispatchers.IO) { readTextFile(context, uri) }
            if (res != null) viewModel.attachFile(res.first, res.second)
            else snackbarHostState.showSnackbar("无法读取该文件（可能是图片/二进制等暂不支持的格式）")
        }
    }
    // Voice input via the system speech recognizer (no RECORD_AUDIO needed — the recognizer app handles it).
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) viewModel.appendInput(spoken)
    }
    // One TTS engine for this screen; reads a reply aloud on demand, released on dispose.
    val tts = remember { Tts(context) }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }

    // Apply an inbound 分享 / 划词 / 磁贴 / 快捷方式 action: open a fresh chat pre-filled with the shared content.
    LaunchedEffect(launchAction) {
        when (val a = launchAction) {
            is LaunchAction.ShareText -> { viewModel.applyShared(a.text, null); onActionConsumed() }
            is LaunchAction.ShareImage -> {
                val dataUrl = withContext(Dispatchers.IO) { encodeImage(context, a.uri) }
                viewModel.applyShared(null, dataUrl)
                if (dataUrl == null) snackbarHostState.showSnackbar("图片读取失败")
                onActionConsumed()
            }
            LaunchAction.NewChat -> { viewModel.newConversation(); onActionConsumed() }
            else -> {}
        }
    }

    // 拍照：full-res capture to a FileProvider uri, then reuse the image-attach pipeline.
    val cameraUri = remember { arrayOfNulls<Uri>(1) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri[0]
        if (ok && uri != null) scope.launch {
            val dataUrl = withContext(Dispatchers.IO) { encodeImage(context, uri) }
            if (dataUrl != null) viewModel.attachImage(dataUrl) else snackbarHostState.showSnackbar("照片读取失败")
        }
    }
    // 扫码 fullscreen overlay visibility.
    var showScanner by remember { mutableStateOf(false) }
    // CAMERA runtime permission → run the deferred camera action (拍照 or 扫码).
    var pendingCameraAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val cameraPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val act = pendingCameraAction; pendingCameraAction = null
        if (granted) act?.invoke()
        else scope.launch { snackbarHostState.showSnackbar("需要相机权限才能拍照 / 扫码") }
    }
    fun withCamera(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) action()
        else { pendingCameraAction = action; cameraPerm.launch(Manifest.permission.CAMERA) }
    }
    fun launchCamera() {
        try {
            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // keep only the current shot — don't accumulate temp files
            val file = File(dir, "shot_" + System.currentTimeMillis() + ".jpg")
            val uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".fileprovider", file)
            cameraUri[0] = uri
            takePicture.launch(uri)
        } catch (_: Exception) {
            scope.launch { snackbarHostState.showSnackbar("无法启动相机") }
        }
    }

    // Dismiss the soft keyboard whenever the drawer opens (via the menu button OR a swipe),
    // otherwise the IME lingers over the drawer.
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) focusManager.clearFocus()
    }

    val lastMsg = state.messages.lastOrNull()
    // Only auto-follow when the user is already near the bottom, so manual scrolling during
    // generation isn't yanked back down.
    val atBottom by remember {
        derivedStateOf {
            val li = listState.layoutInfo
            li.totalItemsCount == 0 || (li.visibleItemsInfo.lastOrNull()?.index ?: -1) >= li.totalItemsCount - 1
        }
    }
    LaunchedEffect(
        state.messages.size,
        lastMsg?.content?.length,
        lastMsg?.reasoning?.length,
        lastMsg?.tools?.size,
        lastMsg?.tools?.lastOrNull()?.status,
        lastMsg?.tools?.lastOrNull()?.result?.length
    ) {
        if (state.messages.isNotEmpty() && atBottom) {
            listState.scrollToItem(state.messages.size - 1, Int.MAX_VALUE)
        }
    }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }
    // When the keyboard opens, pull the conversation down to the latest message so it isn't left
    // hidden behind the (now raised) input bar. Re-pin across the open animation, because the
    // viewport keeps shrinking for ~300ms and a single scroll would settle too early.
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    // derivedStateOf so ChatScreen recomposes only when the keyboard TOGGLES — not on every frame
    // of the open animation (reading getBottom() directly in composition would recompose the whole
    // screen ~18× over 300ms, a major source of the open-animation jank).
    val imeVisible by remember { derivedStateOf { imeInsets.getBottom(density) > 0 } }
    LaunchedEffect(imeVisible) {
        if (imeVisible && state.messages.isNotEmpty() && atBottom) {
            // Re-pin to the latest message ONCE PER FRAME while the IME height is still changing, so
            // the content rides up smoothly in sync with imePadding() instead of snapping in jumps.
            var prev = -1
            var stable = 0
            var guard = 0
            while (stable < 2 && guard < 48) {
                listState.scrollToItem(state.messages.size - 1, Int.MAX_VALUE)
                val cur = imeInsets.getBottom(density)
                if (cur == prev) stable++ else { stable = 0; prev = cur }
                guard++
                withFrameNanos { }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            HistoryDrawer(
                conversations = conversations,
                currentId = state.conversationId,
                onSelect = { id -> viewModel.loadConversation(id); scope.launch { drawerState.close() } },
                onNew = { viewModel.newConversation(); scope.launch { drawerState.close() } },
                onDelete = { viewModel.deleteConversation(it) },
                onRename = { id, title -> viewModel.renameConversation(id, title) },
                onExport = { id -> scope.launch { shareText(context, viewModel.exportMarkdown(id)) } },
                onPin = { id, pinned -> viewModel.setConversationPinned(id, pinned) },
                onTasks = { scope.launch { drawerState.close() }; onOpenTasks() },
                onSettings = { scope.launch { drawerState.close() }; onOpenSettings() }
            )
        }
    ) {
        Scaffold(
            topBar = {
                ChatTopBar(
                    model = state.currentModel,
                    models = state.availableModels,
                    agentEnabled = state.agentEnabled,
                    onToggleAgent = { focusManager.clearFocus(); viewModel.setAgentEnabled(!state.agentEnabled) },
                    onMenu = { focusManager.clearFocus(); scope.launch { drawerState.open() } },
                    onSelectModel = viewModel::selectModel,
                    onManageModels = onOpenModels,
                    onEditPrompt = { focusManager.clearFocus(); showPromptDialog = true }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } }
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize().imePadding()) {
                if (!state.hasApiKey) ApiKeyBanner(onOpenModels)
                if (state.agentEnabled) AgentBanner(state.executionMode) { viewModel.setExecutionMode(it) }

                if (state.messages.isEmpty()) {
                    EmptyState(onSuggestion = viewModel::sendQuick, modifier = Modifier.weight(1f))
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(
                            state.messages,
                            key = { it.id },
                            contentType = { if (it.tools != null) "tools" else if (it.role == Role.USER) "user" else "ai" }
                        ) {
                            Box(Modifier.animateItem()) {
                                MessageBubble(
                                    it,
                                    onManageMemory = onOpenMemory,
                                    onRegenerate = viewModel::regenerate,
                                    onEdit = viewModel::editMessage,
                                    onSpeak = { text -> tts.speak(text) }
                                )
                            }
                        }
                    }
                }

                InputBar(
                    input = state.input,
                    isStreaming = state.isStreaming,
                    pendingImage = state.pendingImage,
                    pendingFileName = state.pendingFileName,
                    onInputChange = viewModel::updateInput,
                    onSend = viewModel::send,
                    onStop = viewModel::stopStreaming,
                    onPickImage = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onPickFile = { filePicker.launch(arrayOf("*/*")) },
                    onVoice = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话…")
                        }
                        try { voiceLauncher.launch(intent) }
                        catch (_: Exception) { scope.launch { snackbarHostState.showSnackbar("此设备没有可用的语音识别") } }
                    },
                    onPhoto = { withCamera { launchCamera() } },
                    onScan = { withCamera { showScanner = true } },
                    onClearImage = viewModel::clearPendingImage,
                    onClearFile = viewModel::clearPendingFile
                )
            }
        }
    }

    if (showScanner) {
        Dialog(
            onDismissRequest = { showScanner = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            QrScannerScreen(
                onResult = { value -> showScanner = false; viewModel.appendInput(value) },
                onClose = { showScanner = false }
            )
        }
    }

    state.pendingTool?.let { pt ->
        AlertDialog(
            onDismissRequest = { viewModel.resolveTool(false) },
            icon = if (pt.danger) {
                { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
            } else null,
            title = { Text(if (pt.danger) "确认高风险操作？" else "允许执行操作？") },
            text = {
                Column {
                    Text(pt.title, fontWeight = FontWeight.SemiBold)
                    Text("工具：${pt.name}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (pt.args.isNotBlank()) {
                        Spacer(Modifier.size(6.dp))
                        Text(pt.args, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (pt.danger) {
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "⚠️ 此操作可能不可撤销（删除 / 写入 / 在电脑上执行命令 / 对外发请求），请确认后再允许。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            lineHeight = 16.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.resolveTool(true) }) {
                    Text("允许", color = if (pt.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = { TextButton(onClick = { viewModel.resolveTool(false) }) { Text("拒绝") } }
        )
    }

    if (showPromptDialog) {
        SystemPromptDialog(
            current = state.systemPromptOverride,
            onDismiss = { showPromptDialog = false },
            onSave = { viewModel.setConversationSystemPrompt(it); showPromptDialog = false }
        )
    }

    if (state.showApiKeyPrompt) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissApiKeyPrompt() },
            title = { Text("还没配置 API Key") },
            text = {
                Text(
                    "「${state.currentModel.displayName}」还没有可用的 API Key。\n" +
                        "Tang 本身不含模型额度，需要你自备各家的 Key（一般按用量付费）。现在去配置？"
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.dismissApiKeyPrompt(); onOpenModels() }) { Text("去配置") } },
            dismissButton = { TextButton(onClick = { viewModel.dismissApiKeyPrompt() }) { Text("取消") } }
        )
    }
}

@Composable
private fun SystemPromptDialog(current: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本对话的系统提示词") },
        text = {
            Column {
                Text(
                    "只对当前这个对话生效；留空则沿用设置里的全局提示词。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 10,
                    placeholder = { Text("例如：你是一名简洁严谨的法律顾问，回答先给结论。") }
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ChatTopBar(
    model: ChatModel,
    models: List<ChatModel>,
    agentEnabled: Boolean,
    onToggleAgent: () -> Unit,
    onMenu: () -> Unit,
    onSelectModel: (String) -> Unit,
    onManageModels: () -> Unit,
    onEditPrompt: () -> Unit
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, contentDescription = "菜单") }
        },
        title = { ModelSelector(model, models, onSelectModel, onManageModels) },
        actions = {
            IconButton(onClick = onEditPrompt) {
                Icon(
                    Icons.Outlined.Tune,
                    contentDescription = "本对话系统提示词",
                    modifier = Modifier.size(21.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onToggleAgent) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = if (agentEnabled) "Agent 已开启" else "开启 Agent 模式",
                    modifier = Modifier.size(21.dp),
                    tint = if (agentEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
        )
    )
}

@Composable
private fun ModelSelector(
    model: ChatModel,
    models: List<ChatModel>,
    onSelect: (String) -> Unit,
    onManage: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    var pickProvider by remember { mutableStateOf<String?>(null) }
    fun prov(m: ChatModel) = m.provider.ifBlank { "其它" }

    Box {
        // Compact pill: model name + a small chevron. Touch target expanded to ≥48dp and exposed as a
        // Button to TalkBack (the visual pill stays small).
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = CircleShape,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clickable { open = true }
                .semantics(mergeDescendants = true) {
                    role = androidx.compose.ui.semantics.Role.Button
                    contentDescription = "切换模型，当前 ${model.displayName}"
                }
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    model.displayName,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 168.dp)
                )
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null, // decorative; the pill itself carries the button label
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Compact rounded card popup (腾讯元宝 style). Two-level: 供应商 → 型号, so it stays small
        // and doesn't cover the screen — no dumping every model at once.
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false; pickProvider = null },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp
        ) {
            Column(Modifier.widthIn(min = 196.dp, max = 248.dp)) {
                val current = pickProvider
                if (current == null) {
                    // Level 1: providers
                    models.map { prov(it) }.distinct().forEach { p ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { pickProvider = p }
                                .padding(start = 16.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                p, fontSize = 13.5.sp, fontWeight = FontWeight.Medium,
                                color = if (prov(model) == p) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Text("${models.count { prov(it) == p }}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { open = false; pickProvider = null; onManage() }
                            .padding(start = 16.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("管理 / 添加供应商", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    // Level 2: models within the chosen provider
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { pickProvider = null }
                            .padding(start = 12.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("‹  $current", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    HorizontalDivider(Modifier.padding(bottom = 2.dp))
                    models.filter { prov(it) == current }.forEach { m ->
                        val selected = m.id == model.id
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { open = false; pickProvider = null; onSelect(m.id) }
                                .padding(start = 16.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                m.displayName, fontSize = 13.5.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (selected) Icon(Icons.Default.Check, "当前", modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryDrawer(
    conversations: List<ConversationEntity>,
    currentId: Long?,
    onSelect: (Long) -> Unit,
    onNew: () -> Unit,
    onDelete: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onExport: (Long) -> Unit,
    onPin: (Long, Boolean) -> Unit,
    onTasks: () -> Unit,
    onSettings: () -> Unit
) {
    val surface = MaterialTheme.colorScheme.surface
    // Leave ~1/4 of the screen for the current conversation instead of a thin sliver.
    val drawerWidth = (LocalConfiguration.current.screenWidthDp * 0.76f).dp
    var pendingDelete by remember { mutableStateOf<ConversationEntity?>(null) }
    var renaming by remember { mutableStateOf<ConversationEntity?>(null) }
    var menuFor by remember { mutableStateOf<Long?>(null) }
    var query by remember { mutableStateOf("") }
    val showSearch = conversations.size >= 6
    val filtered = remember(conversations, query, showSearch) {
        if (!showSearch || query.isBlank()) conversations
        else conversations.filter { it.title.contains(query.trim(), ignoreCase = true) }
    }
    ModalDrawerSheet(drawerContainerColor = surface, modifier = Modifier.width(drawerWidth)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrandMark(size = 26)
            Spacer(Modifier.width(10.dp))
            BrandWordmark(fontSize = 20)
        }
        DrawerRow(Icons.Default.Add, "新对话", onNew)
        DrawerRow(Icons.Default.Notifications, "任务", onTasks)
        Spacer(Modifier.size(6.dp))
        Text(
            "历史会话",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        if (showSearch) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("搜索会话标题", fontSize = 13.sp) },
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
            )
        }
        Box(Modifier.weight(1f)) {
            if (filtered.isEmpty()) {
                Text(
                    "没有匹配的会话",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { c ->
                    val selected = c.id == currentId
                    Box {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 1.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                                .combinedClickable(onClick = { onSelect(c.id) }, onLongClick = { menuFor = c.id })
                                .padding(start = 12.dp, end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                c.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 11.dp)
                            )
                            if (c.pinned) {
                                Icon(
                                    Icons.Default.PushPin,
                                    contentDescription = "已置顶",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        DropdownMenu(expanded = menuFor == c.id, onDismissRequest = { menuFor = null }) {
                            DropdownMenuItem(text = { Text(if (c.pinned) "取消置顶" else "置顶") }, onClick = { menuFor = null; onPin(c.id, !c.pinned) })
                            DropdownMenuItem(text = { Text("重命名") }, onClick = { menuFor = null; renaming = c })
                            DropdownMenuItem(text = { Text("导出 / 分享") }, onClick = { menuFor = null; onExport(c.id) })
                            DropdownMenuItem(text = { Text("删除") }, onClick = { menuFor = null; pendingDelete = c })
                        }
                    }
                }
            }
            // Soft fade at the bottom so the list dissolves into the 设置 row (no hard divider line).
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(28.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, surface)))
            )
        }
        DrawerRow(Icons.Default.Settings, "设置", onSettings)
        Spacer(Modifier.navigationBarsPadding())
    }

    pendingDelete?.let { conv ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这个对话？") },
            text = { Text("「${conv.title}」将被永久删除，无法恢复。") },
            confirmButton = { TextButton(onClick = { onDelete(conv.id); pendingDelete = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }

    renaming?.let { conv ->
        var name by remember(conv.id) { mutableStateOf(conv.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名对话") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = { TextButton(onClick = { onRename(conv.id, name); renaming = null }, enabled = name.isNotBlank()) { Text("保存") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } }
        )
    }
}

/** Compact (~44dp) drawer row: icon + label, rounded press highlight. */
@Composable
private fun DrawerRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(start = 12.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 14.5.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ApiKeyBanner(onOpenModels: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable { onOpenModels() }
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "  当前模型还没配置 API Key，点此到「模型管理」设置 →",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun AgentBanner(mode: ExecutionMode, onModeChange: (ExecutionMode) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    val modeLabel = when (mode) {
        ExecutionMode.CONFIRM_ALL -> "全部确认"
        ExecutionMode.CONFIRM_SIDE_EFFECTS -> "仅副作用确认"
        ExecutionMode.FULL_AUTO -> "完全放权"
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true }
            .semantics {
                role = androidx.compose.ui.semantics.Role.Button
                contentDescription = "Agent 执行模式：$modeLabel，点击切换"
            }
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.SmartToy, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "  Agent 已开启 · $modeLabel",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Spacer(Modifier.weight(1f))
            Text("切换", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = { TextButton(onClick = { showDialog = false }) { Text("关闭") } },
            title = { Text("Agent 执行模式") },
            text = {
                Column {
                    ExecutionMode.entries.forEach { m ->
                        val label = when (m) {
                            ExecutionMode.CONFIRM_ALL -> "全部确认"
                            ExecutionMode.CONFIRM_SIDE_EFFECTS -> "仅副作用确认 ·推荐"
                            ExecutionMode.FULL_AUTO -> "完全放权"
                        }
                        val desc = when (m) {
                            ExecutionMode.CONFIRM_ALL -> "每次调用工具都先问你，最稳。"
                            ExecutionMode.CONFIRM_SIDE_EFFECTS -> "只在改东西 / 发送 / 写文件时才问，读取类直接执行。"
                            ExecutionMode.FULL_AUTO -> "都不问、最快，但模型可不经确认直接读写文件、发请求、操作设备。"
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onModeChange(m) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (mode == m) MaterialTheme.colorScheme.primary else Color.Transparent
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                                Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
                            }
                        }
                    }
                    if (mode == ExecutionMode.FULL_AUTO) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "⚠️ 「完全放权」下模型可不经你确认直接读写文件、发请求、操作手机 / 电脑，请谨慎。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        )
    }
}

private data class Suggestion(val emoji: String, val label: String, val prompt: String)

private val SUGGESTION_POOL = listOf(
    Suggestion("🐙", "GitHub 热门项目收集", "帮我搜集这周 GitHub 上比较热门的几个开源项目，并简要说明它们分别是做什么的。"),
    Suggestion("🏀", "今天 NBA 的比赛情况", "今天有哪些 NBA 比赛？帮我查一下比分和看点。"),
    Suggestion("📱", "看看手机还剩多少空间", "看看我这台手机的存储空间还剩多少。"),
    Suggestion("✈️", "做一份周末出行计划", "帮我做一个本周末两天一夜的周边出行计划，含路线和预算。"),
    Suggestion("📰", "今天有什么大新闻", "帮我汇总一下今天值得关注的几条新闻。"),
    Suggestion("🍳", "用现有食材想个菜", "我有鸡蛋、番茄和米饭，帮我想一道简单的家常菜并给出做法。"),
    Suggestion("💡", "解释一个概念", "用通俗的话给我讲清楚什么是「大语言模型」。"),
    Suggestion("✍️", "帮我润色一段话", "帮我把下面这段话改得更通顺自然：")
)

@Composable
private fun EmptyState(onSuggestion: (String) -> Unit, modifier: Modifier = Modifier) {
    val picks = remember { SUGGESTION_POOL.shuffled().take(4) }
    // Gentle entrance: fade + slight rise. Animated visually (alpha/translation) so the layout stays
    // put — no reflow/re-center jitter as items appear.
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val headerAlpha by animateFloatAsState(if (visible) 1f else 0f, tween(340), label = "emptyHeader")
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Column(Modifier.graphicsLayer { alpha = headerAlpha; translationY = (1f - headerAlpha) * 28f }) {
            BrandMark(size = 44)
            Spacer(Modifier.size(14.dp))
            Text(
                "你好，今天想做什么？",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.size(22.dp))
        picks.forEachIndexed { i, s ->
            val a by animateFloatAsState(
                if (visible) 1f else 0f,
                animationSpec = tween(300, delayMillis = 90 + i * 70),
                label = "card$i"
            )
            SuggestionCard(
                s,
                modifier = Modifier.graphicsLayer { alpha = a; translationY = (1f - a) * 28f }
            ) { onSuggestion(s.prompt) }
            Spacer(Modifier.size(10.dp))
        }
    }
}

@Composable
private fun SuggestionCard(s: Suggestion, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(s.emoji, fontSize = 18.sp)
            Spacer(Modifier.width(12.dp))
            Text(
                s.label,
                fontSize = 14.5.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun InputBar(
    input: String,
    isStreaming: Boolean,
    pendingImage: String?,
    pendingFileName: String?,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onVoice: () -> Unit,
    onPhoto: () -> Unit,
    onScan: () -> Unit,
    onClearImage: () -> Unit,
    onClearFile: () -> Unit
) {
    val active = isStreaming || input.isNotBlank() || pendingImage != null || pendingFileName != null
    var showPlusPanel by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // Floating, rounded input that blends with the background and sits above the gesture bar.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)
    ) {
        if (pendingImage != null) {
            Box(Modifier.padding(start = 8.dp, bottom = 8.dp)) {
                Base64Image(
                    pendingImage,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        .clickable { onClearImage() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, "移除图片", modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.background)
                }
            }
        }
        if (pendingFileName != null) {
            Row(
                modifier = Modifier
                    .padding(start = 8.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "📎 " + pendingFileName.take(28),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier.size(26.dp).clip(CircleShape).clickable { onClearFile() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, "移除文件", modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (!showPlusPanel) focusManager.clearFocus()
                showPlusPanel = !showPlusPanel
            }) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "更多功能",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { if (it.isFocused) showPlusPanel = false },
                placeholder = { Text("发消息…", fontSize = 15.sp) },
                maxLines = 6,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                )
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = active) { if (isStreaming) onStop() else onSend() }
                    .semantics(mergeDescendants = true) { role = androidx.compose.ui.semantics.Role.Button },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isStreaming) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (isStreaming) "停止" else "发送",
                    tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        AnimatedVisibility(
            visible = showPlusPanel,
            enter = expandVertically(animationSpec = tween(240)) + fadeIn(tween(240)),
            exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(tween(140))
        ) {
            PlusPanel(
                onImage = { showPlusPanel = false; onPickImage() },
                onFile = { showPlusPanel = false; onPickFile() },
                onVoice = { showPlusPanel = false; onVoice() },
                onPhoto = { showPlusPanel = false; onPhoto() },
                onScan = { showPlusPanel = false; onScan() }
            )
        }
    }
}

/** WeChat-style "+" panel that slides up below the input (keyboard hidden), with room for future
 *  actions. The input bar rides up because this grows the bottom Column. */
@Composable
private fun PlusPanel(onImage: () -> Unit, onFile: () -> Unit, onVoice: () -> Unit, onPhoto: () -> Unit, onScan: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 14.dp, start = 12.dp, end = 12.dp)
            .height(150.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        PlusPanelItem(Icons.Default.Image, "图片", onImage)
        PlusPanelItem(Icons.Default.Description, "文件", onFile)
        PlusPanelItem(Icons.Default.PhotoCamera, "拍照", onPhoto)
        PlusPanelItem(Icons.Default.QrCodeScanner, "扫码", onScan)
        PlusPanelItem(Icons.Default.Mic, "语音", onVoice)
    }
}

@Composable
private fun PlusPanelItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(62.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Share plain text (a whole conversation exported as Markdown) via the Android share sheet. */
private fun shareText(context: Context, text: String) {
    try {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "分享对话"))
    } catch (_: Exception) {
    }
}

/** Decode a picked image to a downscaled JPEG base64 data-URL (≤~1024px). Runs off the main thread. */
private fun encodeImage(context: Context, uri: Uri): String? = try {
    val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    if (raw == null) null else {
        val original = BitmapFactory.decodeByteArray(raw, 0, raw.size)
        if (original == null) null else {
            val maxDim = 1024
            val scale = minOf(1f, maxDim.toFloat() / maxOf(original.width, original.height))
            val bmp = if (scale < 1f)
                Bitmap.createScaledBitmap(original, (original.width * scale).toInt().coerceAtLeast(1), (original.height * scale).toInt().coerceAtLeast(1), true)
            else original
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            "data:image/jpeg;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        }
    }
} catch (e: Exception) {
    null
}

/** Extract text from a picked file: .pdf/.docx via DocumentTextExtractor, otherwise a bounded
 *  UTF-8 read. Returns (displayName, text) capped to 20k chars, or null if empty/unreadable. */
private fun readTextFile(context: Context, uri: Uri): Pair<String, String>? = try {
    val name = queryDisplayName(context, uri) ?: "file"
    val text = if (DocumentTextExtractor.isSupported(name)) {
        DocumentTextExtractor.extract(context, uri, name)   // .pdf / .docx → extracted text
    } else {
        readPlainText(context, uri)                          // plain-text files
    }
    if (text.isNullOrBlank()) null else name to text.take(20000)
} catch (e: Exception) {
    null
}

/** Bounded UTF-8 read of a plain-text file; null if empty or it contains NUL bytes (i.e. binary). */
private fun readPlainText(context: Context, uri: Uri): String? = try {
    context.contentResolver.openInputStream(uri)?.use { ins ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < 400_000) {
            val r = ins.read(buf)
            if (r < 0) break
            out.write(buf, 0, r); total += r
        }
        out.toByteArray().toString(Charsets.UTF_8)
    }?.takeIf { s -> s.none { it.code == 0 } }
} catch (e: Exception) {
    null
}

private fun queryDisplayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
} catch (e: Exception) {
    null
}
