package com.dschat.app.agent.tasks

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dschat.app.App
import java.util.Calendar

/** The actual periodic work: morning bad-weather push + weather-change alert. Best-effort. */
object WeatherMonitor {

    suspend fun runCheck(ctx: Context) {
        val container = (ctx.applicationContext as? App)?.container ?: return
        val settings = container.settings
        if (!settings.weatherMonitorEnabled.value) { Log.d("WeatherMon", "skip: monitor disabled"); return }

        val r = WeatherApi.fetch(ctx, settings, null, null, null, 1)
        if (r == null) { Log.d("WeatherMon", "skip: fetch null (city='${settings.weatherCity.value}', no location?)"); return }
        Log.d("WeatherMon", "fetched place=${r.place} now=${r.nowText} ${r.nowTemp}° cat=${r.category} max=${r.todayMax} min=${r.todayMin} aqi=${r.aqi} warn=${r.warning}")
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val today = cal.get(Calendar.DAY_OF_YEAR)

        // 1) Morning push (once per day, any time at/after the chosen hour — catches up if the worker was late).
        if (hour >= settings.weatherMorningHour.value && settings.lastMorningPushDay != today) {
            settings.lastMorningPushDay = today
            val msg = badWeatherMsg(r)
            Log.d("WeatherMon", "morning push: hour=$hour badMsg=${msg ?: "(none-good weather)"}")
            msg?.let { notify(ctx, "今日天气", it) }
        } else {
            Log.d("WeatherMon", "no morning push (hour=$hour morningHour=${settings.weatherMorningHour.value} lastPushDay=${settings.lastMorningPushDay} today=$today)")
        }

        // 2) Change alert vs the last snapshot.
        val snap = "${r.category}|${r.todayMax}|${r.todayMin}|${r.warning ?: ""}"
        val prev = settings.weatherSnapshot
        if (prev.isNotBlank() && prev != snap) {
            changeMsg(prev, r)?.let { msg ->
                if (System.currentTimeMillis() - settings.lastChangeNotifiedAt > 3 * 3_600_000L) {
                    settings.lastChangeNotifiedAt = System.currentTimeMillis()
                    notify(ctx, "天气变化", msg)
                }
            }
        }
        settings.weatherSnapshot = snap
    }

    /** Returns a notification body if today's weather is noteworthy; null = stay silent. */
    private fun badWeatherMsg(r: WeatherReport): String? {
        val parts = ArrayList<String>()
        r.warning?.let { parts.add("⚠️ $it") }
        when (r.category) {
            "雷" -> parts.add("有雷阵雨，注意安全 ⛈️")
            "雪" -> parts.add("有雪，注意保暖防滑 ❄️")
            "雨" -> parts.add("有雨，记得带伞 ☔")
            "雾霾" -> parts.add("有雾霾，戴口罩注意能见度 😷")
        }
        if (r.precipProb in 60..100 && r.category !in setOf("雨", "雪", "雷")) parts.add("降水概率 ${r.precipProb}%，建议带伞")
        if (r.todayMax >= 35) parts.add("最高 ${r.todayMax}°，高温防暑 🥵")
        if (r.todayMin <= -5) parts.add("最低 ${r.todayMin}°，注意保暖 🧥")
        if (r.aqi in 150..1000) parts.add("空气 AQI ${r.aqi} 偏差，戴口罩")
        if (parts.isEmpty()) return null
        return "${r.place} ${r.nowText} ${r.todayMin}~${r.todayMax}°\n" + parts.joinToString("；")
    }

    /** Returns a change-alert body, or null if the change isn't worth notifying. */
    private fun changeMsg(prevSnap: String, r: WeatherReport): String? {
        val prevCat = prevSnap.substringBefore('|')
        val prevWarn = prevSnap.substringAfterLast('|')
        // New official warning that wasn't there before.
        if (r.warning != null && r.warning != prevWarn) return "⚠️ 新预警：${r.warning}（${r.place}）"
        // Category change involving precipitation is the useful signal.
        if (prevCat != r.category && (r.category in setOf("雨", "雪", "雷") || prevCat in setOf("雨", "雪", "雷"))) {
            return "${r.place} 天气由「$prevCat」转「${r.category}」（当前 ${r.nowText} ${r.nowTemp}°）"
        }
        return null
    }

    private fun notify(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val n = NotificationCompat.Builder(ctx, WeatherScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        nm.notify("weather".hashCode() and 0xffff, n)
    }
}
