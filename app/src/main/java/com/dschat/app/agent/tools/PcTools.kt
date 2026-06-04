package com.dschat.app.agent.tools

import com.dschat.app.agent.Tool
import com.dschat.app.agent.ToolLimits
import com.dschat.app.agent.capWithMarker
import com.dschat.app.agent.intOr
import com.dschat.app.agent.intProp
import com.dschat.app.agent.objectSchema
import com.dschat.app.agent.str
import com.dschat.app.agent.strProp
import com.dschat.app.pc.PcBridge
import kotlinx.serialization.json.JsonObject

/** Friendly error text when an SSH/SFTP op fails (model reads this and can adjust). */
private fun pcError(e: Throwable): String =
    "错误：电脑操作失败 —— ${e.message ?: e.javaClass.simpleName}。请确认已开启电脑控制模式、电脑可达，且 SSH（地址/用户名/密钥）配置正确。"

/** Run a single command line on the connected PC and return its output. */
class PcRunTool(private val pc: PcBridge) : Tool {
    override val name = "pc_run"
    override val description =
        "在已连接的电脑上执行一条命令行命令（Windows 默认 cmd），返回标准输出、错误输出与退出码。" +
            "用于在电脑上跑命令、查看信息、运行脚本、做文件批处理等。需要用户已开启「电脑控制模式」。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "command" to strProp("要在电脑上执行的命令，例如 `dir D:\\` 或 `git -C C:\\proj status`"),
        "timeout_sec" to intProp("最长等待秒数，默认 30", 1, 300),
        required = listOf("command")
    )

    override suspend fun execute(args: JsonObject): String {
        val cmd = args.str("command")
        if (cmd.isBlank()) return "错误：command 不能为空"
        val r = runCatching { pc.exec(cmd, args.intOr("timeout_sec", 30) * 1000) }
            .getOrElse { return pcError(it) }
        return buildString {
            append("退出码：").append(r.exitCode)
            if (r.stdout.isNotBlank()) append("\n[stdout]\n").append(r.stdout.capWithMarker(ToolLimits.HTTP_BODY))
            if (r.stderr.isNotBlank()) append("\n[stderr]\n").append(r.stderr.capWithMarker(2000))
            if (r.stdout.isBlank() && r.stderr.isBlank()) append("\n（无输出）")
        }
    }
}

class PcListDirTool(private val pc: PcBridge) : Tool {
    override val name = "pc_list_dir"
    override val description = "列出电脑上某个目录下的文件与子目录。需要已开启电脑控制模式。"
    override val sideEffect = false
    override fun parameters() = objectSchema(
        "path" to strProp("电脑上的目录路径，例如 `C:/Users/Administrator/Desktop`（也可用反斜杠）"),
        required = listOf("path")
    )

    override suspend fun execute(args: JsonObject): String {
        val path = args.str("path")
        if (path.isBlank()) return "错误：path 不能为空"
        return runCatching { pc.sftpList(path).capWithMarker(ToolLimits.HTTP_BODY) }.getOrElse { pcError(it) }
    }
}

class PcReadFileTool(private val pc: PcBridge) : Tool {
    override val name = "pc_read_file"
    override val description = "读取电脑上一个文本文件的内容。需要已开启电脑控制模式。"
    override val sideEffect = false
    override fun parameters() = objectSchema(
        "path" to strProp("电脑上的文件路径"),
        required = listOf("path")
    )

    override suspend fun execute(args: JsonObject): String {
        val path = args.str("path")
        if (path.isBlank()) return "错误：path 不能为空"
        return runCatching { pc.sftpReadText(path).capWithMarker(ToolLimits.HTTP_BODY) }.getOrElse { pcError(it) }
    }
}

class PcWriteFileTool(private val pc: PcBridge) : Tool {
    override val name = "pc_write_file"
    override val description = "把文本内容写入电脑上的一个文件（覆盖）。需要已开启电脑控制模式。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "path" to strProp("电脑上的目标文件路径"),
        "content" to strProp("要写入的文本内容"),
        required = listOf("path", "content")
    )

    override suspend fun execute(args: JsonObject): String {
        val path = args.str("path")
        if (path.isBlank()) return "错误：path 不能为空"
        return runCatching { pc.sftpWriteText(path, args.str("content")); "已写入电脑文件：$path" }.getOrElse { pcError(it) }
    }
}

class PcUploadFileTool(private val pc: PcBridge) : Tool {
    override val name = "pc_upload_file"
    override val description = "把手机本地的一个文件上传到电脑指定路径。需要已开启电脑控制模式。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "local_path" to strProp("手机本地文件路径，例如 /sdcard/Download/a.pdf"),
        "remote_path" to strProp("电脑上的目标路径，例如 C:/Users/Administrator/Desktop/a.pdf"),
        required = listOf("local_path", "remote_path")
    )

    override suspend fun execute(args: JsonObject): String {
        val local = args.str("local_path")
        val remote = args.str("remote_path")
        if (local.isBlank() || remote.isBlank()) return "错误：local_path 与 remote_path 都不能为空"
        return runCatching { pc.sftpUpload(local, remote); "已上传到电脑：$remote" }.getOrElse { pcError(it) }
    }
}

class PcDownloadFileTool(private val pc: PcBridge) : Tool {
    override val name = "pc_download_file"
    override val description = "把电脑上的一个文件下载到手机目录。需要已开启电脑控制模式。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "remote_path" to strProp("电脑上的文件路径"),
        "local_dir" to strProp("手机上的保存目录，例如 /sdcard/Download"),
        required = listOf("remote_path", "local_dir")
    )

    override suspend fun execute(args: JsonObject): String {
        val remote = args.str("remote_path")
        val dir = args.str("local_dir")
        if (remote.isBlank() || dir.isBlank()) return "错误：remote_path 与 local_dir 都不能为空"
        return runCatching { "已下载到手机：" + pc.sftpDownload(remote, dir) }.getOrElse { pcError(it) }
    }
}
