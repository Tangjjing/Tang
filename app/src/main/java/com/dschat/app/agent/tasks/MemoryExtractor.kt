package com.dschat.app.agent.tasks

import com.dschat.app.data.remote.AgentMessage
import com.dschat.app.data.remote.DeepSeekApi
import com.dschat.app.data.settings.SettingsRepository
import com.dschat.app.domain.MemoryExtraction
import kotlinx.serialization.json.Json

/**
 * After a chat turn, decides whether the user's latest message contains durable facts worth
 * remembering and returns add/update/skip ops. Cheap flash model, JSON-only, fail-silent —
 * mirrors [TaskClassifier]. The "when to store" intelligence lives entirely in SYSTEM_PROMPT.
 */
class MemoryExtractor(
    private val api: DeepSeekApi,
    private val settings: SettingsRepository
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Returns the proposed memory ops, or null on any failure / nothing to do. */
    suspend fun extract(userText: String, assistantText: String?): MemoryExtraction? {
        if (userText.trim().length < MIN_USER_CHARS) return null
        val model = pickModel()
        if (!settings.hasKeyFor(model)) return null

        val existing = settings.memories.value
        val existingBlock = if (existing.isEmpty()) "（暂无已有记忆）"
        else existing.joinToString("\n") { m ->
            val cat = if (m.category.isBlank()) "" else "[${m.category}]"
            "- [id=${m.id}]$cat ${m.title.take(20)}：${m.content.take(120)}"
        }

        val userMsg = buildString {
            append("已有的长期记忆（用于判断是否重复或需要更新；update 时务必填对应的 id）：\n").append(existingBlock)
            if (!assistantText.isNullOrBlank()) {
                append("\n\n助手上一条回复（仅作上下文，绝不要从中抽取事实）：\n").append(assistantText.take(500))
            }
            append("\n\n用户最新消息：\n").append(userText.take(1200))
        }

        val messages = listOf(
            AgentMessage(role = "system", content = SYSTEM_PROMPT),
            AgentMessage(role = "user", content = userMsg)
        )
        val (key, url) = settings.credsFor(model)
        val resp = try {
            api.chatCompletion(
                apiKey = key,
                baseUrl = url,
                model = model,
                messages = messages,
                temperature = 0.1,
                tools = null,
                onUsage = { p, c -> settings.recordUsage(model, p, c) }
            )
        } catch (e: Exception) {
            return null
        }
        val raw = extractJson(resp.content.orEmpty())
        if (raw.isBlank()) return null
        return try {
            json.decodeFromString(MemoryExtraction.serializer(), raw)
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
        private const val MIN_USER_CHARS = 8
        private val SYSTEM_PROMPT = """
            你是用户长期记忆的「守门人」。下面会给你：用户已有的长期记忆列表、（可选）助手上一条回复、以及用户的最新一条消息。判断这条用户消息里是否含有「值得长期记住的、关于用户本人的事实」，并输出对记忆库的增改操作。

            【该记（关于用户本人、长期有效）】
            - 身份与基本情况：职业、所在城市、母语、所学专业等
            - 稳定的偏好与习惯：回答风格、技术栈、口味、作息、忌口、惯用工具
            - 环境与设备：常用手机/电脑型号、系统、常用软件
            - 长期目标与承诺：在学的语言、长期项目、健身/学习计划
            - 重要的人与关系：家人/同事的称呼与关系（仅当用户明确提到且长期有用）
            - 用户明确要求记住的内容（出现「记住…」「以后都…」「我是…」等）

            【绝不要记（直接 skip）】
            - 一次性的问题、闲聊、寒暄、情绪宣泄
            - 本次任务的结果、过程、待办、临时状态（如「帮我查一下…」「这个报错怎么解决」）
            - 几天内就会过期的信息（今天的天气、今晚的安排）
            - 助手回复里的内容（事实只能来自用户）
            - 已经被现有记忆覆盖、且没有新增信息的内容

            【力度=适中】明确的长期事实，以及明显的偏好/习惯/背景都可以记；但要过滤掉一次性与临时内容。拿不准时倾向于不记。

            【去重与更新】
            - 若新事实与某条已有记忆是同一主题：用 op="update" 并带上那条的 id，content 写「合并后的完整内容」（保留仍然成立的旧信息 + 新信息），不要只写增量。
            - 若用户明确改变了之前的偏好（如「我现在改用 Kotlin 了」），用 update 覆盖那条旧记忆。
            - 若是全新主题，用 op="add"。
            - 没有任何值得记的，ops 用空数组。

            【类别】每条记忆都给一个简短的中文类别标签 category，从这些里挑最贴切的：个人信息、编码偏好、饮食、环境设备、目标计划、人际关系、其它。

            【标记相关记忆】另外返回 referencedIds —— 本次用户消息用到 / 相关的【已有记忆】的 id 列表（即使没有任何新增或更新也要给）；用来记录哪些记忆仍然有用。没有相关的就给空数组。

            【输出】只输出一个 JSON 对象，不要解释、不要代码块标记：
            {"ops":[{"op":"add","category":"个人信息","title":"关于我","content":"用户是 Android 开发者，常用 Kotlin 和 Jetpack Compose。"}],"referencedIds":[]}
            更新示例：{"ops":[{"op":"update","id":3,"category":"编码偏好","title":"编程偏好","content":"以前用 Java，现在主要用 Kotlin + Jetpack Compose。"}],"referencedIds":[3]}
            没有可记但用到了已有记忆：{"ops":[],"referencedIds":[1,5]}
            完全无关：{"ops":[],"referencedIds":[]}
        """.trimIndent()
    }
}
