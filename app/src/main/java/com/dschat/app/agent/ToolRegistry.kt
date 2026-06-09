package com.dschat.app.agent

import android.content.Context
import com.dschat.app.agent.tools.CalendarCreateTool
import com.dschat.app.agent.tools.CalendarReadTool
import com.dschat.app.agent.tools.AppUsageTool
import com.dschat.app.agent.tools.AskUserTool
import com.dschat.app.agent.tools.MakeCallTool
import com.dschat.app.agent.tools.SendSmsTool
import com.dschat.app.agent.tools.ContactsTool
import com.dschat.app.agent.tools.DateTimeTool
import com.dschat.app.agent.tools.ImageToPdfTool
import com.dschat.app.agent.tools.DeleteFileTool
import com.dschat.app.agent.tools.DeviceInfoTool
import com.dschat.app.agent.tools.DocumentToPdfTool
import com.dschat.app.agent.tools.MergePdfsTool
import com.dschat.app.agent.tools.PdfToImagesTool
import com.dschat.app.agent.tools.PdfToWordTool
import com.dschat.app.agent.tools.SplitPdfTool
import com.dschat.app.agent.tools.FetchUrlTool
import com.dschat.app.agent.tools.FetchUrlsTool
import com.dschat.app.agent.tools.FindAppTool
import com.dschat.app.agent.tools.GetWeatherTool
import com.dschat.app.agent.tools.FindFilesTool
import com.dschat.app.agent.tools.ForgetMemoryTool
import com.dschat.app.agent.tools.GetClipboardTool
import com.dschat.app.agent.tools.HttpRequestTool
import com.dschat.app.agent.tools.ListFilesTool
import com.dschat.app.agent.tools.LocationTool
import com.dschat.app.agent.tools.OpenAppTool
import com.dschat.app.agent.tools.OpenUrlTool
import com.dschat.app.agent.tools.PcDownloadFileTool
import com.dschat.app.agent.tools.PcListDirTool
import com.dschat.app.agent.tools.PcReadFileTool
import com.dschat.app.agent.tools.PcRunTool
import com.dschat.app.agent.tools.PcUploadFileTool
import com.dschat.app.agent.tools.PcWriteFileTool
import com.dschat.app.agent.tools.ReadFileTool
import com.dschat.app.agent.tools.ReadMemoryTool
import com.dschat.app.agent.tools.RunJavascriptTool
import com.dschat.app.agent.tools.SaveMemoryTool
import com.dschat.app.agent.tools.SendNotificationTool
import com.dschat.app.agent.tools.SetAlarmTool
import com.dschat.app.agent.tools.SetClipboardTool
import com.dschat.app.agent.tools.SetReminderTool
import com.dschat.app.agent.tools.ShareTextTool
import com.dschat.app.agent.tools.WebSearchTool
import com.dschat.app.agent.tools.WriteFileTool
import com.dschat.app.data.settings.SettingsRepository
import com.dschat.app.pc.PcBridge
import kotlinx.serialization.json.JsonObject

class ToolRegistry(context: Context, private val settings: SettingsRepository, pcBridge: PcBridge) {

    private val app = context.applicationContext

    private val all: List<Tool> = listOf(
        // networking
        WebSearchTool(settings), FetchUrlTool(), FetchUrlsTool(), HttpRequestTool(), GetWeatherTool(app, settings),
        // files
        ReadFileTool(), WriteFileTool(), ListFilesTool(), DeleteFileTool(), FindFilesTool(),
        // file conversion (offline, text-level for Office formats)
        ImageToPdfTool(app), DocumentToPdfTool(app), PdfToWordTool(app),
        PdfToImagesTool(app), MergePdfsTool(app), SplitPdfTool(app),
        // memory
        SaveMemoryTool(settings), ReadMemoryTool(settings), ForgetMemoryTool(settings),
        // utility
        DateTimeTool(), DeviceInfoTool(app), RunJavascriptTool(), AskUserTool(),
        // device
        GetClipboardTool(app), SetClipboardTool(app), ShareTextTool(app),
        OpenUrlTool(app), OpenAppTool(app), FindAppTool(app),
        SetReminderTool(app), SendNotificationTool(app),
        MakeCallTool(app), SendSmsTool(app), AppUsageTool(app),
        // permissioned
        LocationTool(app), CalendarReadTool(app), CalendarCreateTool(app),
        ContactsTool(app), SetAlarmTool(app),
        // PC control (SSH) — only surfaced to the model when control mode is on
        PcRunTool(pcBridge), PcListDirTool(pcBridge), PcReadFileTool(pcBridge),
        PcWriteFileTool(pcBridge), PcUploadFileTool(pcBridge), PcDownloadFileTool(pcBridge)
    )

    val allTools: List<Tool> get() = all

    fun enabled(): List<Tool> = all.filter { t ->
        if (!settings.isToolEnabled(t.name)) return@filter false
        // Hide PC-control tools entirely unless the user has turned on control mode.
        if (t.name.startsWith("pc_") && !settings.pcControlEnabled.value) return@filter false
        true
    }

    /** tools[] for the API, or null if none enabled. */
    fun apiSchemas(): List<JsonObject>? = enabled().takeIf { it.isNotEmpty() }?.map { it.toApiSchema() }

    fun find(name: String): Tool? = all.firstOrNull { it.name == name }
}
