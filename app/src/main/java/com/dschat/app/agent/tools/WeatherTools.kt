package com.dschat.app.agent.tools

import android.content.Context
import com.dschat.app.agent.Tool
import com.dschat.app.agent.intOr
import com.dschat.app.agent.intProp
import com.dschat.app.agent.objectSchema
import com.dschat.app.agent.strOrNull
import com.dschat.app.agent.doubleOrNull
import com.dschat.app.agent.numberProp
import com.dschat.app.agent.strProp
import com.dschat.app.agent.tasks.WeatherApi
import com.dschat.app.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

class GetWeatherTool(private val context: Context, private val settings: SettingsRepository) : Tool {
    override val name = "get_weather"
    override val description = "查询天气：当前实况、未来几天预报、空气质量（配置了和风 key 时还含官方灾害预警）。位置可传 city（城市名）或 lat+lon；都不传则用本机定位 / 已设的固定城市。"
    override val sideEffect = false
    override fun parameters() = objectSchema(
        "city" to strProp("城市名（如 杭州），可选"),
        "lat" to numberProp("纬度（与 lon 一起，可选）", -90.0, 90.0),
        "lon" to numberProp("经度（与 lat 一起，可选）", -180.0, 180.0),
        "days" to intProp("预报天数，默认 3，最多 7", 1, 7),
        required = emptyList()
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val days = args.intOr("days", 3).coerceIn(1, 7)
        val city = args.strOrNull("city")
        val lat = args.doubleOrNull("lat")
        val lon = args.doubleOrNull("lon")
        val r = WeatherApi.fetch(context, settings, city, lat, lon, days)
            ?: return@withContext "暂时拿不到天气：请确认网络，或传入 city/lat+lon，或在 设置→权限 开启定位（也可在 设置→天气 设一个固定城市）。"
        buildString {
            append(r.place).append("：当前 ").append(r.nowText).append(' ').append(r.nowTemp).append('°')
            append("，今天 ").append(r.todayMin).append('~').append(r.todayMax).append('°')
            if (r.precipProb >= 0) append("，降水概率 ").append(r.precipProb).append('%')
            if (r.aqi >= 0) append("，空气 AQI ").append(r.aqi)
            append('\n')
            if (r.warning != null) append("⚠️ 预警：").append(r.warning).append('\n')
            if (r.forecast.isNotEmpty()) append("预报：\n").append(r.forecast.joinToString("\n"))
        }.trim()
    }
}
