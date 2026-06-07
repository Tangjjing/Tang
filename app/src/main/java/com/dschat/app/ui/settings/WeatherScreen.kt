@file:OptIn(ExperimentalMaterial3Api::class)

package com.dschat.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dschat.app.agent.tasks.WeatherScheduler

@Composable
fun WeatherScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val monitorOn by viewModel.weatherMonitorEnabled.collectAsStateWithLifecycle()
    val city by viewModel.weatherCity.collectAsStateWithLifecycle()
    val hour by viewModel.weatherMorningHour.collectAsStateWithLifecycle()
    val qwKey by viewModel.qweatherKey.collectAsStateWithLifecycle()
    val qwHost by viewModel.qweatherHost.collectAsStateWithLifecycle()
    val checking by viewModel.weatherChecking.collectAsStateWithLifecycle()
    val checkResult by viewModel.weatherCheckResult.collectAsStateWithLifecycle()
    val context = LocalContext.current

    SettingsSubScreen("天气", onBack) {
        SectionTitle("数据源（和风天气，可选）")
        OutlinedTextField(
            value = qwKey,
            onValueChange = viewModel::setQweatherKey,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("和风天气 API Key（含官方灾害预警）") },
            placeholder = { Text("不填则用免费的 Open-Meteo（无预警）") }
        )
        OutlinedTextField(
            value = qwHost,
            onValueChange = viewModel::setQweatherHost,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            singleLine = true,
            label = { Text("和风 API Host") },
            placeholder = { Text("devapi.qweather.com") }
        )
        Hint("和风每个账号有专属 API Host（在控制台“设置”里看），把它填到上面（默认 devapi.qweather.com）。不填 key 时用 Open-Meteo，免费但没有中国官方气象预警。")

        SectionTitle("固定城市（定位拿不到时用）")
        OutlinedTextField(
            value = city,
            onValueChange = viewModel::setWeatherCity,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("城市名，如 杭州") },
            placeholder = { Text("留空则只用实时定位") }
        )

        SectionTitle("早间提醒时间")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(6, 7, 8, 9, 10).forEach { h ->
                FilterChip(selected = hour == h, onClick = { viewModel.setWeatherMorningHour(h) }, label = { Text("%02d:00".format(h)) })
            }
        }

        SectionTitle("测试")
        OutlinedButton(
            onClick = { viewModel.checkWeatherNow(context) },
            enabled = !checking,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (checking) "检查中…" else "立即检查天气") }
        checkResult?.let { Hint(it) }

        SectionTitle("后台天气监控")
        ToggleRow(
            "早间恶劣天气 + 突变提醒",
            "每天清晨若有雨雪/高温/低温/重污染/官方预警，推送一条通知；天气突变（如转雨、新预警）也会提醒。",
            monitorOn
        ) {
            viewModel.setWeatherMonitorEnabled(it)
            if (it) WeatherScheduler.enqueue(context) else WeatherScheduler.cancel(context)
        }
        if (monitorOn) {
            Hint("⚠️ 后台监控约每 15 分钟检查一次（系统会按省电策略放宽间隔，不保证准时）。小米/HyperOS 需开启本应用『自启动』并把电池设为『无限制』，否则后台会被杀、收不到提醒。")
            OutlinedButton(
                onClick = {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                        )
                    } catch (_: Exception) {
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("打开应用设置（设置电池『无限制』/ 自启动）") }
        }
    }
}
