package app.deckr

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.viewModelScope
import app.deckr.ble.DeckrConnection
import app.deckr.ble.LinkState
import kotlinx.coroutines.launch
import app.deckr.ble.Protocol
import app.deckr.ble.PadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class Mode { Touchpad, Gamepad }

class DeckrViewModel(app: Application) : AndroidViewModel(app) {
    val link = DeckrConnection(app)
    private val prefs = app.getSharedPreferences("deckr", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(Mode.Touchpad)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    /** Random id made on first launch. The PC remembers it once you allow this phone. */
    private val deviceId: UUID = prefs.getString("deviceId", null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: UUID.randomUUID().also { prefs.edit().putString("deviceId", it.toString()).apply() }

    /** The name shown on the PC, e.g. "Pixel 8". */
    private val deviceName: String =
        Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    val lastAddress: String? get() = prefs.getString("lastAddress", null)
    val lastName: String? get() = prefs.getString("lastName", null)

    /** Connect to the last PC by itself whenever it shows up. Off after the user disconnects. */
    @Volatile var autoConnect = true

    var sensitivity: Float
        get() = prefs.getFloat("sensitivity", 1.6f)
        set(value) = prefs.edit().putFloat("sensitivity", value).apply()

    // ---- gamepad state ----
    private var pad = PadState()

    private var pending: Pair<String, String>? = null

    init {
        // Remember a PC only once it actually let us in, so trying a wrong device from the
        // nearby list doesn't become the auto-connect target.
        viewModelScope.launch {
            link.state.collect { s ->
                if (s == LinkState.Connected) pending?.let { (address, name) ->
                    prefs.edit().putString("lastAddress", address).putString("lastName", name).apply()
                }
            }
        }
        link.onFound = { pc ->
            if (autoConnect && (pc.address == lastAddress || (lastName != null && pc.name == lastName))) {
                connect(pc.address, pc.name)
            }
        }
    }

    fun connect(address: String, name: String) {
        autoConnect = true
        pending = address to name
        link.connect(address, name, Protocol.hello(deviceId, deviceName))
    }

    /** The user chose to disconnect, so don't jump straight back in. */
    fun disconnect() {
        autoConnect = false
        releasePad()
        link.disconnect()
    }

    fun setMode(mode: Mode) {
        if (mode != Mode.Gamepad && _mode.value == Mode.Gamepad) releasePad()
        _mode.value = mode
    }

    fun padButton(bit: Int, down: Boolean) = updatePad(pad.withButton(bit, down))
    fun padDpad(bit: Int, down: Boolean) = updatePad(pad.withDpad(bit, down))
    fun leftStick(x: Float, y: Float) = updatePad(pad.copy(lx = x, ly = y))
    fun rightStick(x: Float, y: Float) = updatePad(pad.copy(rx = x, ry = y))
    fun l2(value: Float) = updatePad(pad.copy(l2 = value))
    fun r2(value: Float) = updatePad(pad.copy(r2 = value))

    @Synchronized
    private fun updatePad(next: PadState) {
        if (next == pad) return
        pad = next
        link.pad(next)
    }

    fun releasePad() = updatePad(PadState())

    override fun onCleared() {
        releasePad()
        link.close()
    }
}
