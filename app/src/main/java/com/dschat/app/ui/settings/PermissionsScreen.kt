@file:OptIn(ExperimentalMaterial3Api::class)

package com.dschat.app.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh++ }

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    val allFiles = remember(refresh) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
    val locationOk = remember(refresh) { granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION) }
    val calendarOk = remember(refresh) { granted(Manifest.permission.READ_CALENDAR) }
    val contactsOk = remember(refresh) { granted(Manifest.permission.READ_CONTACTS) }
    val appsOk = remember(refresh) { granted("com.android.permission.GET_INSTALLED_APPS") }
    val notifyOk = remember(refresh) {
        Build.VERSION.SDK_INT < 33 || granted("android.permission.POST_NOTIFICATIONS")
    }
    val callOk = remember(refresh) { granted(Manifest.permission.CALL_PHONE) }
    val smsOk = remember(refresh) { granted(Manifest.permission.SEND_SMS) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("权限") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Agent 的工具需要这些权限。按需开启即可——文件类工具需要「所有文件访问」。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            PermCard("所有文件访问", "读写整个手机存储（read_file / write_file 等必需）", allFiles) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                        )
                    } catch (e: Exception) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                } else {
                    launcher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE))
                }
            }

            PermCard("定位", "get_location 工具", locationOk) {
                launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            PermCard("日历（读写）", "read_calendar 读取 + create_calendar_event 静默写入日程", calendarOk) {
                launcher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
            }
            PermCard("通讯录", "search_contacts 工具", contactsOk) {
                launcher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
            }
            PermCard("读取应用列表", "find_app 枚举全部应用（小米/HyperOS 需要；常见 App 无需此项也能打开）", appsOk) {
                launcher.launch(arrayOf("com.android.permission.GET_INSTALLED_APPS"))
            }
            if (Build.VERSION.SDK_INT >= 33) {
                PermCard("通知", "到点提醒需要（通知助理用）", notifyOk) {
                    launcher.launch(arrayOf("android.permission.POST_NOTIFICATIONS"))
                }
            }
            PermCard("打电话", "make_call 的 direct=true 直接拨出需要（默认只打开拨号盘，无需此项）", callOk) {
                launcher.launch(arrayOf(Manifest.permission.CALL_PHONE))
            }
            PermCard("发短信", "send_sms 的 direct=true 直接发出需要（默认只打开短信应用，无需此项）", smsOk) {
                launcher.launch(arrayOf(Manifest.permission.SEND_SMS))
            }

            OutlinedButton(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth()) {
                Text("刷新授权状态")
            }
            Text(
                "提示：从系统设置授权「所有文件访问」后，回到这里点「刷新」即可看到最新状态。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PermCard(title: String, subtitle: String, granted: Boolean, onGrant: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (granted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (granted) {
                Text("已授权", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            } else {
                Button(onClick = onGrant) { Text("授权") }
            }
        }
    }
}
