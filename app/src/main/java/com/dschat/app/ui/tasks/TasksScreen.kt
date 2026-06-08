@file:OptIn(ExperimentalMaterial3Api::class)

package com.dschat.app.ui.tasks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dschat.app.data.local.TaskEntity
import com.dschat.app.data.local.TaskStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TasksScreen(viewModel: TasksViewModel, onBack: () -> Unit, onOpenNotify: () -> Unit = {}) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(toast) {
        toast?.let { snackbar.showSnackbar(it); viewModel.clearToast() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("任务") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    if (tasks.any { it.status == TaskStatus.DONE }) {
                        TextButton(onClick = { viewModel.clearFinished() }) { Text("清除已完成") }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (tasks.isEmpty()) {
            Box(
                Modifier.padding(padding).fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Notifications, null,
                        Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.size(10.dp))
                    Text("还没有自动识别到的待办", color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "开启「通知助理」后，重要通知会自动变成这里的任务",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.size(12.dp))
                    FilledTonalButton(onClick = onOpenNotify) { Text("去开启通知助理") }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(tasks, key = { it.id }) {
                    Box(Modifier.animateItem()) { TaskCard(it, viewModel) }
                }
            }
        }
    }
}

@Composable
private fun TaskCard(task: TaskEntity, vm: TasksViewModel) {
    val done = task.status == TaskStatus.DONE
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PriorityTag(task.priority)
                Spacer(Modifier.width(8.dp))
                Text(
                    task.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
            }
            if (task.detail.isNotBlank()) {
                Text(task.detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                buildString {
                    append("来自 ").append(task.sourceLabel)
                    task.dueAt?.let { append(" · ").append(relativeTime(it)) }
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!done) {
                task.suggestedReply?.let { reply ->
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text("建议回复", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.size(2.dp))
                            Text(reply, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    FilledTonalButton(onClick = { vm.sendReply(task) }) {
                        Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("发送回复")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { vm.complete(task.id) }) { Text("完成") }
                    TextButton(onClick = { vm.snooze(task.id, 60) }) { Text("推迟1时") }
                    TextButton(onClick = { vm.dismiss(task.id) }) { Text("忽略") }
                }
            } else {
                Text("已完成", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun PriorityTag(priority: Int) {
    val label = when (priority) { 2 -> "高"; 1 -> "中"; else -> "低" }
    val high = priority == 2
    // 高=实心黑底白字；中/低=描边 chip（卡片本身是 surfaceVariant，纯填充几乎看不见，故加 1dp 描边）。
    val bg = if (high) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface
    val fg = if (high) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = bg,
        shape = RoundedCornerShape(6.dp),
        border = if (high) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Text(
            label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

private fun relativeTime(dueAt: Long): String {
    val now = System.currentTimeMillis()
    val diff = dueAt - now
    val mins = diff / 60_000L
    return when {
        diff < -60_000L -> "已过期"
        mins < 60 -> "${mins.coerceAtLeast(0)} 分钟后"
        mins < 60 * 12 -> "${mins / 60} 小时后"
        else -> SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(dueAt))
    }
}
