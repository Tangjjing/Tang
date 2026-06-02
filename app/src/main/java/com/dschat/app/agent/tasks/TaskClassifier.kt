package com.dschat.app.agent.tasks

import com.dschat.app.data.remote.AgentMessage
import com.dschat.app.data.remote.DeepSeekApi
import com.dschat.app.data.settings.SettingsRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class TaskJudgement(
    val isTask: Boolean = false,
    val title: String = "",
    val detail: String = "",
    val priority: String = "medium",   // high / medium / low
    val dueInMinutes: Int = -1,        // minutes from now; -1 = no specific time
    val suggestedReply: String = "",   // optional draft reply; "" = none
    val appointment: Boolean = false,  // a concrete, timed appointment/meeting/deadline → calendar-worthy
    val durationMinutes: Int = 60      // rough event length in minutes
)

/**
 * Classifies a single notification into a task judgement via a cheap LLM call.
 * Runs on a background notification; returns null on any failure (fail-silent).
 */
class TaskClassifier(
    private val api: DeepSeekApi,
    private val settings: SettingsRepository
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun classify(appLabel: String, title: String, text: String): TaskJudgement? {
        if (!settings.hasApiKey()) return null
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", Locale.getDefault()).format(Date())
        val messages = listOf(
            AgentMessage(role = "system", content = SYSTEM_PROMPT),
            AgentMessage(
                role = "user",
                content = "当前时间：$now\n来源应用：$appLabel\n通知标题：$title\n通知内容：$text"
            )
        )
        val resp = try {
            api.chatCompletion(
                apiKey = settings.apiKey.value,
                baseUrl = settings.baseUrl.value,
                model = pickModel(),
                messages = messages,
                temperature = 0.1,
                tools = null
            )
        } catch (e: Exception) {
            return null
        }
        val raw = extractJson(resp.content.orEmpty())
        if (raw.isBlank()) return null
        return try {
            json.decodeFromString(TaskJudgement.serializer(), raw)
        } catch (e: Exception) {
            null
        }
    }

    /** Prefer a cheap flash model for this background micro-task; fall back to the user's pick. */
    private fun pickModel(): String {
        val models = settings.models.value
        return models.firstOrNull { it.id == "deepseek-v4-flash" }?.id
            ?: models.firstOrNull { it.id.contains("flash", ignoreCase = true) }?.id
            ?: settings.currentModel().id
    }

    private fun extractJson(s: String): String {
        var t = s.trim()
        if (t.startsWith("```")) {
            t = t.substringAfter('\n', "").substringBeforeLast("```").trim()
        }
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        return if (start >= 0 && end > start) t.substring(start, end + 1) else t
    }

    companion object {
        private val SYSTEM_PROMPT = """
            你是一个手机信息分析器。输入可能是一条通知，也可能是从截图里识别出的文字（可能含界面噪音）。判断它是否代表用户「需要处理的事项」（待办 / 约定 / 截止 / 需要回复的消息 / 账单或验证码以外的提醒等），并抽取结构化信息。截图文字若只是普通界面、文章、聊天记录而无明确待办，isTask 填 false。
            只输出一个 JSON 对象，不要任何解释、不要代码块标记。字段：
            - isTask: 布尔。是待办/需处理则 true；纯资讯、广告、营销、系统提示、验证码、已读无需动作的内容则 false。
            - title: 简短的事项标题（不超过 20 字），只描述「要做的事」本身。注意：通知标题往往是发件人/联系人的名字，绝不要把人名、App 名写进 title。例如内容是「张经理：明天下午两点开会」→ title 应为「开会」或「项目会议」，而不是「张经理开会」。
            - detail: 一句话说明要做什么（可空字符串）。
            - priority: "high" | "medium" | "low"。涉及明确截止时间、领导/重要联系人、金钱、紧急用词 → high；闲聊/可选 → low；其余 medium。
            - dueInMinutes: 整数。若通知含明确时间（如“下午3点”“明天10点”“30分钟后”“周五前”），换算成「距现在的分钟数」；无明确时间填 -1。
            - suggestedReply: 若这是一条需要「回复」的人际消息，给出一条得体、简短的中文建议回复；否则填 ""。
            - appointment: 布尔。仅当内容是【有明确未来时间】的真实安排（会议/约会/面试/预约/航班/截止/取票等）时为 true；只是模糊提醒、没具体时间、或周期性事项 → false。务必保守：拿不准就 false。
            - durationMinutes: 事件大致时长（分钟），没说就填 60。
            示例1：{"isTask":true,"title":"回复张三吃饭安排","detail":"张三问今晚是否一起吃饭","priority":"medium","dueInMinutes":-1,"suggestedReply":"好的，今晚几点？在哪见？","appointment":false,"durationMinutes":60}
            示例2（下午两点开会，假设现在距下午两点 180 分钟）：{"isTask":true,"title":"项目会议","detail":"下午2点开项目会","priority":"high","dueInMinutes":180,"suggestedReply":"","appointment":true,"durationMinutes":60}
        """.trimIndent()
    }
}
