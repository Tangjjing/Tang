package com.dschat.app.agent.tools

import com.dschat.app.agent.Tool
import com.dschat.app.agent.arrayProp
import com.dschat.app.agent.boolProp
import com.dschat.app.agent.objectSchema
import com.dschat.app.agent.strProp
import kotlinx.serialization.json.JsonObject

/**
 * 让模型在「信息不足」时弹出选项卡片请用户点选（类似 Claude 的 AskUserQuestion），而不是用纯文字反问。
 *
 * 本工具不在 [execute] 里真正运行：ChatViewModel 的 agent 循环按名字特判它，弹出内联选项卡片、挂起等
 * 用户点选，再把用户的选择作为工具结果回喂给模型。这里的 execute 只是兜底（正常路径不会被调到）。
 */
class AskUserTool : Tool {
    override val name = "ask_user"
    override val description =
        "当你需要用户补充关键信息才能继续、且该信息有歧义或没有明显默认值时（尤其搜索/研究类请求，用户给的条件不全），" +
        "不要用纯文字反问，而是调用本工具：给一个简短问题 + 2~5 个候选项，用户点一下即可作答。" +
        "可设 multi_select=true 允许多选；默认带「其它」让用户手填。一次只问一个最关键的问题；" +
        "信息已够、或有显而易见的默认理解时，绝不要调用，直接动手。"
    override val sideEffect = false

    override fun parameters(): JsonObject = objectSchema(
        "question" to strProp("要问用户的简短问题，一句话。"),
        "options" to arrayProp("2~5 个候选项，每个尽量短（几个字），覆盖最常见的选择。"),
        "multi_select" to boolProp("是否允许多选（用户可勾选多个再确定）。默认 false：单选，点一下即继续。"),
        "allow_other" to boolProp("是否额外提供「其它」让用户手填自定义内容。默认 true。"),
        required = listOf("question", "options")
    )

    override suspend fun execute(args: JsonObject): String =
        "（ask_user 须由客户端处理；此处未捕获到用户的选择。）"
}
