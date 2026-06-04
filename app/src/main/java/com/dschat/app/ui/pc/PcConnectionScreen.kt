@file:OptIn(ExperimentalMaterial3Api::class)

package com.dschat.app.ui.pc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dschat.app.pc.PcState
import com.dschat.app.ui.settings.Hint
import com.dschat.app.ui.settings.SectionTitle
import com.dschat.app.ui.settings.SettingsSubScreen
import com.dschat.app.ui.settings.ToggleRow

@Composable
fun PcConnectionScreen(viewModel: PcViewModel, onBack: () -> Unit) {
    val control by viewModel.controlEnabled.collectAsStateWithLifecycle()
    val host by viewModel.host.collectAsStateWithLifecycle()
    val port by viewModel.port.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val transport by viewModel.transport.collectAsStateWithLifecycle()
    val publicKey by viewModel.publicKey.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    SettingsSubScreen("电脑连接", onBack) {
        ToggleRow(
            "电脑控制模式",
            "开启后，助手才能调用「在电脑上执行命令 / 读写文件 / 互传文件」等工具；关闭即全部隐藏。",
            control
        ) { viewModel.setControlEnabled(it) }

        HorizontalDivider()

        SectionTitle("连接方式")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = transport == "wifi", onClick = { viewModel.setTransport("wifi") }, label = { Text("Wi-Fi（同一局域网）") })
            FilterChip(selected = transport == "usb", onClick = { viewModel.setTransport("usb") }, label = { Text("有线（USB 网络共享）") })
        }
        if (transport == "wifi") {
            Hint("电脑与手机连同一 Wi-Fi。在电脑上用 ipconfig 查看局域网 IP（如 192.168.x.x），填到下方地址。")
        } else {
            Hint("先在手机「设置→个人热点→USB 网络共享」打开共享，再点下方「自动探测」找到电脑地址（通常 192.168.42.x）。")
            OutlinedButton(onClick = { viewModel.detectUsb() }, enabled = !busy) { Text(if (busy) "探测中…" else "自动探测电脑地址") }
        }

        SectionTitle("电脑地址")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = host, onValueChange = { viewModel.setHost(it) },
                label = { Text("地址 / IP") }, singleLine = true,
                modifier = Modifier.weight(2f)
            )
            OutlinedTextField(
                value = if (port > 0) port.toString() else "", onValueChange = { viewModel.setPort(it.toIntOrNull() ?: 22) },
                label = { Text("端口") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }
        OutlinedTextField(
            value = user, onValueChange = { viewModel.setUser(it) },
            label = { Text("电脑登录用户名") }, placeholder = { Text("如 Administrator") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        SectionTitle("本机公钥（贴到电脑一次）")
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                text = publicKey.ifBlank { "生成中…" },
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(10.dp)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(publicKey)) }, enabled = publicKey.isNotBlank()) { Text("复制公钥") }
            OutlinedButton(onClick = { viewModel.regenerateKey() }) { Text("重新生成") }
        }

        HorizontalDivider()

        Button(onClick = { viewModel.test() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "连接中…" else "测试连接")
        }
        val (statusText, statusColor) = when (val s = state) {
            is PcState.Connected -> "✅ 已连接：${s.host}" to MaterialTheme.colorScheme.primary
            is PcState.Connecting -> "连接中…" to MaterialTheme.colorScheme.onSurfaceVariant
            is PcState.Error -> "❌ ${s.message}" to MaterialTheme.colorScheme.error
            PcState.Disconnected -> "未连接" to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(statusText, color = statusColor, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)

        HorizontalDivider()
        SectionTitle("电脑端一次性配置（Windows）")
        Hint(
            "1) 以管理员 PowerShell 启用 OpenSSH 服务端：\n" +
                "   Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0\n" +
                "2) 启动并设为自启：Start-Service sshd; Set-Service -StartupType Automatic sshd\n" +
                "3) 把上面的公钥粘到电脑：\n" +
                "   • 普通账户 → C:\\Users\\<用户>\\.ssh\\authorized_keys\n" +
                "   • 管理员账户 → C:\\ProgramData\\ssh\\administrators_authorized_keys\n" +
                "4) 回到本页填地址/用户名，点「测试连接」。"
        )
    }
}
