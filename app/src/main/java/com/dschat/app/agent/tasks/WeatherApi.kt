package com.dschat.app.agent.tasks

import android.content.Context
import com.dschat.app.agent.tools.LocationUtil
import com.dschat.app.agent.tools.ToolHttp
import com.dschat.app.data.settings.SettingsRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.net.URLEncoder

/** Normalized weather result shared by the get_weather tool and the proactive monitor. */
data class WeatherReport(
    val place: String,
    val nowText: String,
    val nowTemp: Int,
    val category: String,        // 晴/多云/阴/雨/雪/雷/雾霾/其它
    val todayMax: Int,
    val todayMin: Int,
    val precipProb: Int,         // %, -1 unknown
    val aqi: Int,                // -1 unknown
    val warning: String?,        // official alert title (QWeather only)
    val forecast: List<String>   // one line per day
)

object WeatherApi {

    /** Resolve a usable (lat, lon, displayName). Priority: explicit args → city → home city → device. */
    suspend fun resolve(
        ctx: Context, settings: SettingsRepository, city: String?, lat: Double?, lon: Double?
    ): Triple<Double, Double, String>? {
        if (lat != null && lon != null) return Triple(lat, lon, city?.ifBlank { null } ?: "指定坐标")
        val wantCity = city?.ifBlank { null } ?: settings.weatherCity.value.ifBlank { null }
        if (wantCity != null) geocode(settings, wantCity)?.let { return it }
        val loc = LocationUtil.currentLocation(ctx)
        if (loc != null) return Triple(loc.latitude, loc.longitude, "当前位置")
        // last resort: if a home city was set but geocoding failed above, we already returned null there
        return null
    }

    /** Fetch + normalize. QWeather (with key, incl. official warnings) preferred, else Open-Meteo. */
    suspend fun fetch(
        ctx: Context, settings: SettingsRepository, city: String?, lat: Double?, lon: Double?, days: Int
    ): WeatherReport? {
        val (la, lo, place) = resolve(ctx, settings, city, lat, lon) ?: return null
        val key = settings.qweatherKey.value
        return if (key.isNotBlank()) qweather(settings, la, lo, place, days) ?: openMeteo(la, lo, place, days)
        else openMeteo(la, lo, place, days)
    }

    private fun getJson(url: String): JsonObject? = try {
        ToolHttp.client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful || body.isBlank()) null else ToolHttp.json.parseToJsonElement(body).jsonObject
        }
    } catch (e: Exception) {
        null
    }

    // ---- QWeather (和风) ----
    private fun geocode(settings: SettingsRepository, city: String): Triple<Double, Double, String>? {
        val enc = URLEncoder.encode(city, "UTF-8")
        val key = settings.qweatherKey.value
        if (key.isNotBlank()) {
            val host = settings.qweatherHost.value.removePrefix("https://").removePrefix("http://").trimEnd('/')
            // New per-account hosts (*.qweatherapi.com) serve GeoAPI at /geo/v2; legacy keys used geoapi.qweather.com.
            for (u in listOf(
                "https://$host/geo/v2/city/lookup?location=$enc&number=1&key=$key",
                "https://geoapi.qweather.com/v2/city/lookup?location=$enc&number=1&key=$key"
            )) {
                val o = getJson(u) ?: continue
                if (o["code"]?.jsonPrimitive?.contentOrNull != "200") continue
                val loc = o["location"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                val lat = loc["lat"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                val lon = loc["lon"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                val name = loc["name"]?.jsonPrimitive?.contentOrNull ?: city
                if (lat != null && lon != null) return Triple(lat, lon, name)
            }
        }
        // Open-Meteo geocoding (no key)
        val o = getJson("https://geocoding-api.open-meteo.com/v1/search?name=$enc&count=1&language=zh&format=json")
        val r = o?.get("results")?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val lat = r["latitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null
        val lon = r["longitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null
        return Triple(lat, lon, r["name"]?.jsonPrimitive?.contentOrNull ?: city)
    }

    private fun qweather(settings: SettingsRepository, lat: Double, lon: Double, place: String, days: Int): WeatherReport? {
        val key = settings.qweatherKey.value
        val host = settings.qweatherHost.value.removePrefix("https://").removePrefix("http://").trimEnd('/')
        val loc = "${"%.2f".format(lon)},${"%.2f".format(lat)}" // QWeather wants lon,lat
        val now = getJson("https://$host/v7/weather/now?location=$loc&key=$key") ?: return null
        if (now["code"]?.jsonPrimitive?.contentOrNull != "200") return null
        val n = now["now"]?.jsonObject ?: return null
        val nowText = n["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val nowTemp = n["temp"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val dailyRange = if (days >= 7) "7d" else "3d"
        val daily = getJson("https://$host/v7/weather/$dailyRange?location=$loc&key=$key")?.get("daily")?.jsonArray
        val list = ArrayList<String>()
        var tMax = nowTemp; var tMin = nowTemp; var precip = -1
        daily?.take(days)?.forEachIndexed { i, e ->
            val d = e.jsonObject
            val max = d["tempMax"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val min = d["tempMin"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val td = d["textDay"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val pop = d["precip"]?.jsonPrimitive?.contentOrNull
            if (i == 0) { tMax = max; tMin = min }
            list.add("${dayLabel(i)} $td $min~$max°")
        }
        val aqi = getJson("https://$host/v7/air/now?location=$loc&key=$key")
            ?.get("now")?.jsonObject?.get("aqi")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -1
        val warn = getJson("https://$host/v7/warning/now?location=$loc&key=$key")
            ?.get("warning")?.jsonArray?.firstOrNull()?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull
        return WeatherReport(place, nowText, nowTemp, categorize(nowText), tMax, tMin, precip, aqi, warn, list)
    }

    // ---- Open-Meteo (free, no key) ----
    private fun openMeteo(lat: Double, lon: Double, place: String, days: Int): WeatherReport? {
        val o = getJson(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=${days.coerceIn(1, 7)}"
        ) ?: return null
        val cur = o["current"]?.jsonObject
        val nowTemp = cur?.get("temperature_2m")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 0
        val nowCode = cur?.get("weather_code")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val (cat, nowText) = wmo(nowCode)
        val daily = o["daily"]?.jsonObject
        val codes = daily?.get("weather_code")?.jsonArray
        val maxs = daily?.get("temperature_2m_max")?.jsonArray
        val mins = daily?.get("temperature_2m_min")?.jsonArray
        val pops = daily?.get("precipitation_probability_max")?.jsonArray
        val list = ArrayList<String>()
        var tMax = nowTemp; var tMin = nowTemp; var precip = -1
        val n = codes?.size ?: 0
        for (i in 0 until minOf(n, days)) {
            val mx = maxs?.getOrNull(i)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 0
            val mn = mins?.getOrNull(i)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 0
            val pp = pops?.getOrNull(i)?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -1
            val cd = codes?.getOrNull(i)?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            if (i == 0) { tMax = mx; tMin = mn; precip = pp }
            list.add("${dayLabel(i)} ${wmo(cd).second} $mn~$mx°${if (pp >= 0) " 降水${pp}%" else ""}")
        }
        val aqi = getJson("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon&current=us_aqi")
            ?.get("current")?.jsonObject?.get("us_aqi")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt() ?: -1
        return WeatherReport(place, nowText, nowTemp, cat, tMax, tMin, precip, aqi, null, list)
    }

    private fun dayLabel(i: Int): String = when (i) {
        0 -> "今天"; 1 -> "明天"; 2 -> "后天"; else -> "第${i + 1}天"
    }

    private fun categorize(text: String): String = when {
        text.contains("雷") -> "雷"
        text.contains("雪") -> "雪"
        text.contains("雨") -> "雨"
        text.contains("雾") || text.contains("霾") || text.contains("沙") -> "雾霾"
        text.contains("阴") -> "阴"
        text.contains("云") -> "多云"
        text.contains("晴") -> "晴"
        else -> "其它"
    }

    /** WMO weather_code → (category, Chinese text). */
    private fun wmo(code: Int): Pair<String, String> = when (code) {
        0 -> "晴" to "晴"
        1, 2 -> "多云" to "多云"
        3 -> "阴" to "阴"
        45, 48 -> "雾霾" to "雾"
        51, 53, 55, 56, 57 -> "雨" to "毛毛雨"
        61, 63, 65, 66, 67, 80, 81, 82 -> "雨" to "雨"
        71, 73, 75, 77, 85, 86 -> "雪" to "雪"
        95, 96, 99 -> "雷" to "雷阵雨"
        else -> "其它" to "未知"
    }
}
