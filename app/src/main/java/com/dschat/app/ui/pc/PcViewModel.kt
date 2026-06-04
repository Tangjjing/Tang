package com.dschat.app.ui.pc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dschat.app.data.settings.SettingsRepository
import com.dschat.app.pc.PcBridge
import com.dschat.app.pc.PcState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PcViewModel(
    private val settings: SettingsRepository,
    private val bridge: PcBridge
) : ViewModel() {

    val controlEnabled: StateFlow<Boolean> = settings.pcControlEnabled
    val host: StateFlow<String> = settings.pcHost
    val port: StateFlow<Int> = settings.pcPort
    val user: StateFlow<String> = settings.pcUser
    val transport: StateFlow<String> = settings.pcTransport
    val publicKey: StateFlow<String> = settings.pcPublicKey
    val state: StateFlow<PcState> = bridge.state

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    init {
        // Generate the keypair the first time this screen is opened, off the main thread.
        viewModelScope.launch(Dispatchers.IO) { runCatching { bridge.ensureKeyPair() } }
    }

    fun setControlEnabled(v: Boolean) = settings.setPcControlEnabled(v)
    fun setHost(v: String) = settings.setPcHost(v)
    fun setPort(v: Int) = settings.setPcPort(v)
    fun setUser(v: String) = settings.setPcUser(v)
    fun setTransport(v: String) = settings.setPcTransport(v)

    fun regenerateKey() = viewModelScope.launch(Dispatchers.IO) { runCatching { bridge.regenerateKeyPair() } }

    fun test() = viewModelScope.launch {
        _busy.value = true
        bridge.connect()
        _busy.value = false
    }

    fun detectUsb() = viewModelScope.launch {
        _busy.value = true
        val found = withContext(Dispatchers.IO) { bridge.detectUsbHost(settings.pcPort.value) }
        if (found != null) settings.setPcHost(found)
        _busy.value = false
    }
}
