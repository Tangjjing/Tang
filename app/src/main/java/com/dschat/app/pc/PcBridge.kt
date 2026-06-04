package com.dschat.app.pc

import com.dschat.app.data.settings.SettingsRepository
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Properties

/** Connection state for the "control PC" feature, surfaced to the settings UI. */
sealed interface PcState {
    data object Disconnected : PcState
    data object Connecting : PcState
    data class Connected(val host: String) : PcState
    data class Error(val message: String) : PcState
}

/** Result of running one command on the PC. */
data class ExecResult(val stdout: String, val stderr: String, val exitCode: Int)

/**
 * Single SSH/SFTP connection to the user's PC, shared across the whole app (one per [SettingsRepository]).
 * Key-only auth: a keypair is generated on the device, the private key lives in encrypted prefs, the
 * public key is pasted onto the PC once. All blocking work runs on [Dispatchers.IO] and is serialized
 * by [lock] so concurrent agent tool calls reuse the same session safely.
 */
class PcBridge(private val settings: SettingsRepository) {

    private val _state = MutableStateFlow<PcState>(PcState.Disconnected)
    val state: StateFlow<PcState> = _state.asStateFlow()

    /** Master "control mode" switch (mirrors settings); pc_ tools are hidden when false. */
    val controlEnabled: StateFlow<Boolean> get() = settings.pcControlEnabled

    private val jsch = JSch()
    private var session: Session? = null
    private val lock = Mutex()

    // ---- keys ----

    /** Generate the keypair on first use; returns the OpenSSH public-key line to paste onto the PC. */
    fun ensureKeyPair(): String {
        settings.pcPublicKey.value.takeIf { it.isNotBlank() }?.let { return it }
        val kp = KeyPair.genKeyPair(jsch, KeyPair.RSA, 3072)
        val priv = ByteArrayOutputStream().also { kp.writePrivateKey(it) }.toByteArray().toString(Charsets.UTF_8)
        val pub = ByteArrayOutputStream().also { kp.writePublicKey(it, "tang-app") }.toByteArray().toString(Charsets.UTF_8).trim()
        kp.dispose()
        settings.setPcKeyPair(priv, pub)
        return pub
    }

    fun regenerateKeyPair(): String {
        settings.setPcKeyPair("", "")
        settings.setPcHostKey("") // host trust is per-key context; reset so the next connect re-pins
        return ensureKeyPair()
    }

    fun publicKey(): String = settings.pcPublicKey.value.ifBlank { ensureKeyPair() }

    // ---- connection ----

    private fun connectInternal(): Session {
        session?.let { if (it.isConnected) return it }
        val host = settings.pcHost.value.trim()
        val user = settings.pcUser.value.trim()
        val port = settings.pcPort.value
        require(host.isNotEmpty()) { "未填写电脑地址" }
        require(user.isNotEmpty()) { "未填写电脑用户名" }
        ensureKeyPair()
        jsch.removeAllIdentity()
        jsch.addIdentity("tang", settings.pcPrivateKey.value.toByteArray(), settings.pcPublicKey.value.toByteArray(), null)
        val s = jsch.getSession(user, host, port)
        s.setConfig(Properties().apply {
            setProperty("StrictHostKeyChecking", "no") // we pin the key ourselves (TOFU) below
            setProperty("PreferredAuthentications", "publickey")
        })
        s.connect(CONNECT_TIMEOUT_MS)
        // Trust-on-first-use: remember the host key, reject silent changes afterwards.
        val fp = runCatching { s.hostKey?.getFingerPrint(jsch) }.getOrNull().orEmpty()
        val known = settings.pcHostKey.value
        if (fp.isNotEmpty()) {
            if (known.isBlank()) settings.setPcHostKey(fp)
            else if (known != fp) { s.disconnect(); throw IllegalStateException("电脑主机指纹与上次不一致（可能存在风险）。若确实更换了电脑/重装了系统，请在设置里重置后重连。") }
        }
        session = s
        return s
    }

    suspend fun connect(): Result<String> = withContext(Dispatchers.IO) {
        lock.withLock {
            _state.value = PcState.Connecting
            runCatching { connectInternal().host }
                .onSuccess { _state.value = PcState.Connected(it) }
                .onFailure { _state.value = PcState.Error(it.message ?: "连接失败") }
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        lock.withLock {
            session?.disconnect()
            session = null
            _state.value = PcState.Disconnected
        }
    }

    // ---- exec ----

    suspend fun exec(command: String, timeoutMs: Int = EXEC_TIMEOUT_MS): ExecResult = withContext(Dispatchers.IO) {
        lock.withLock {
            val s = connectInternal()
            _state.value = PcState.Connected(s.host)
            val ch = s.openChannel("exec") as ChannelExec
            ch.setCommand(command)
            val err = ByteArrayOutputStream()
            ch.setErrStream(err)
            val input = ch.inputStream
            ch.connect()
            val out = input.readBytes() // reads until the channel closes (EOF)
            var waited = 0
            while (!ch.isClosed && waited < timeoutMs) { Thread.sleep(20); waited += 20 }
            val code = ch.exitStatus
            ch.disconnect()
            ExecResult(out.toString(Charsets.UTF_8), err.toByteArray().toString(Charsets.UTF_8), code)
        }
    }

    // ---- sftp ----

    private suspend fun <T> sftp(block: (ChannelSftp) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            val s = connectInternal()
            _state.value = PcState.Connected(s.host)
            val ch = s.openChannel("sftp") as ChannelSftp
            ch.connect(CONNECT_TIMEOUT_MS)
            try { block(ch) } finally { ch.disconnect() }
        }
    }

    suspend fun sftpList(path: String): String = sftp { ch ->
        val p = path.ifBlank { "." }
        buildString {
            for (obj in ch.ls(p)) {
                val e = obj as ChannelSftp.LsEntry
                if (e.filename == "." || e.filename == "..") continue
                val dir = e.attrs.isDir
                append(if (dir) "📁 " else "📄 ").append(e.filename)
                if (!dir) append("  (").append(e.attrs.size).append(" B)")
                append("\n")
            }
        }.ifBlank { "（空目录）" }
    }

    suspend fun sftpReadText(path: String): String = sftp { ch ->
        ch.get(path).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    suspend fun sftpWriteText(path: String, content: String): Unit = sftp { ch ->
        ch.put(ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)), path)
    }

    /** Phone file → PC path. */
    suspend fun sftpUpload(localPath: String, remotePath: String): Unit = sftp { ch ->
        val f = File(localPath)
        require(f.exists()) { "手机本地文件不存在：$localPath" }
        FileInputStream(f).use { ch.put(it, remotePath) }
    }

    /** PC file → phone directory; returns the saved local path. */
    suspend fun sftpDownload(remotePath: String, localDir: String): String = sftp { ch ->
        val name = remotePath.replace('\\', '/').substringAfterLast('/').ifBlank { "download.bin" }
        val dir = File(localDir).apply { mkdirs() }
        val dest = File(dir, name)
        ch.get(remotePath, dest.absolutePath)
        dest.absolutePath
    }

    // ---- USB tether auto-detect ----

    /** Scan the standard Android USB-tether subnet (192.168.42.x) for a host with [port] open. */
    suspend fun detectUsbHost(port: Int): String? = withContext(Dispatchers.IO) {
        for (i in 1..254) {
            val ip = "192.168.42.$i"
            val ok = runCatching {
                Socket().use { it.connect(InetSocketAddress(ip, port), 120); true }
            }.getOrDefault(false)
            if (ok) return@withContext ip
        }
        null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8000
        const val EXEC_TIMEOUT_MS = 30000
    }
}
