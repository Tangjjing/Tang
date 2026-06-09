package com.dschat.app.agent.tasks

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dschat.app.App
import com.dschat.app.data.remote.FundApi
import java.util.Calendar

/** Daily portfolio P&L push: a gentle morning summary + an optional evening (fresh-NAV) one. */
object PortfolioMonitor {

    suspend fun runCheck(ctx: Context) {
        val container = (ctx.applicationContext as? App)?.container ?: return
        val settings = container.settings
        if (!settings.portfolioEnabled.value) { Log.d("PortfolioMon", "skip: disabled"); return }
        val holdings = settings.holdings.value
        if (holdings.isEmpty()) { Log.d("PortfolioMon", "skip: no holdings"); return }

        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val today = cal.get(Calendar.DAY_OF_YEAR)
        val morningDue = hour >= settings.portfolioMorningHour.value && settings.lastPortfolioMorningDay != today
        val eveningDue = settings.portfolioEveningEnabled.value &&
            hour >= settings.portfolioEveningHour.value && settings.lastPortfolioEveningDay != today
        // Evening hour is later in the day; if both happen to be due (e.g. first run at night), the
        // evening one wins and we also mark morning done so we don't double-fire the same data.
        if (!morningDue && !eveningDue) {
            Log.d("PortfolioMon", "no push (hour=$hour mDue=$morningDue eDue=$eveningDue)")
            return
        }

        val quotes = FundApi.fetchQuotes(holdings.map { it.code })
        if (quotes.isEmpty()) { Log.d("PortfolioMon", "skip: quotes empty (retry later)"); return }
        val rows = PortfolioReport.rows(holdings, quotes)

        if (eveningDue) {
            settings.lastPortfolioEveningDay = today
            settings.lastPortfolioMorningDay = today // tonight's NAV already covers today's morning slot
            notify(ctx, "🌙 今日收盘 · 持仓", PortfolioReport.headline(rows),
                PortfolioReport.summary("晚上好，今天收盘后的情况是这样：", rows))
        } else {
            settings.lastPortfolioMorningDay = today
            notify(ctx, "🌅 早安 · 持仓", PortfolioReport.headline(rows),
                PortfolioReport.summary("早安～昨天收盘后，你的持仓是这样的：", rows))
        }
    }

    /** Fetch & push one summary right now, ignoring the time gate. For the screen's test button. */
    suspend fun pushNow(ctx: Context): String {
        val container = (ctx.applicationContext as? App)?.container ?: return "应用未就绪"
        val holdings = container.settings.holdings.value
        if (holdings.isEmpty()) return "还没有持仓"
        val quotes = FundApi.fetchQuotes(holdings.map { it.code })
        if (quotes.isEmpty()) return "暂时取不到净值，请稍后再试"
        val rows = PortfolioReport.rows(holdings, quotes)
        notify(ctx, "📊 持仓速览", PortfolioReport.headline(rows),
            PortfolioReport.summary("你当前的持仓情况：", rows))
        return "已发送一条通知，看看通知栏 🤍"
    }

    private fun notify(ctx: Context, title: String, collapsed: String, full: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val n = NotificationCompat.Builder(ctx, PortfolioScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(title)
            .setContentText(collapsed)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(full)
                    .setBigContentTitle(title)
                    .setSummaryText("点开看每只明细")
            )
            .setAutoCancel(true)
            .build()
        nm.notify("portfolio".hashCode() and 0xffff, n)
    }
}
