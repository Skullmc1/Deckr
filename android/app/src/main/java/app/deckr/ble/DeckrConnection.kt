package app.deckr.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue

data class FoundPc(val address: String, val name: String, val rssi: Int)

enum class LinkState { Idle, Scanning, Connecting, Approval, Connected, Denied, NotDeckr, Failed }

/**
 * Talks to Deckr for Windows over Bluetooth LE.
 *
 * Android allows one outstanding GATT operation at a time, so every packet goes through a single
 * writer loop. Mouse movement, scrolling and gamepad state are coalesced: if the link is busy,
 * new deltas are added up and the latest pad state replaces the old one, so input never lags behind.
 */
@SuppressLint("MissingPermission")
class DeckrConnection(private val context: Context) {

    private val tag = "Deckr"
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager?.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _found = MutableStateFlow<List<FoundPc>>(emptyList())
    val found: StateFlow<List<FoundPc>> = _found.asStateFlow()

    /** Every Bluetooth device the scan hears, for the "show all nearby devices" fallback. */
    private val _nearby = MutableStateFlow<List<FoundPc>>(emptyList())
    val nearby: StateFlow<List<FoundPc>> = _nearby.asStateFlow()

    private val _connectedName = MutableStateFlow<String?>(null)
    val connectedName: StateFlow<String?> = _connectedName.asStateFlow()

    val bluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    private var gatt: BluetoothGatt? = null
    private var input: BluetoothGattCharacteristic? = null
    private var status: BluetoothGattCharacteristic? = null
    private var maxPacket = 20
    private var hello = ByteArray(0)

    /** Called for every PC the scan sees, so the app can connect to a known one by itself. */
    var onFound: ((FoundPc) -> Unit)? = null
    private var sessionJob: Job? = null
    private var scanJob: Job? = null

    // ---- outgoing data, guarded by `lock` ----
    private val lock = Any()
    private val queue = ConcurrentLinkedQueue<ByteArray>()
    private var moveX = 0f
    private var moveY = 0f
    private var scrollV = 0f
    private var scrollH = 0f
    private var pad: PadState? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // ---- GATT callback plumbing ----
    private val setupEvents = Channel<String>(Channel.UNLIMITED)
    private val writeDone = Channel<Int>(Channel.CONFLATED)
    private val readDone = Channel<ByteArray?>(Channel.CONFLATED)

    // ==================================================================== scanning

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord
            val name = record?.deviceName ?: runCatching { result.device.name }.getOrNull()
            // Filtered here rather than with a hardware ScanFilter: some phones' filters never
            // match 128-bit service UUIDs, which made the PC invisible.
            val isDeckr = record?.serviceUuids?.any { it.uuid == Protocol.SERVICE } == true
            val seen = FoundPc(result.device.address, name ?: if (isDeckr) "Windows PC" else "Unnamed device", result.rssi)
            _nearby.value = (_nearby.value.filterNot { it.address == seen.address } + seen)
                .sortedByDescending { it.rssi }.take(60)
            if (!isDeckr) return
            val pc = seen
            _found.value = (_found.value.filterNot { it.address == pc.address } + pc).sortedByDescending { it.rssi }
            if (_state.value == LinkState.Scanning) onFound?.invoke(pc)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(tag, "Scan failed: $errorCode")
            if (_state.value == LinkState.Scanning) _state.value = LinkState.Failed
        }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run { _state.value = LinkState.Failed; return }
        stopScan()
        _found.value = emptyList()
        _nearby.value = emptyList()
        _state.value = LinkState.Scanning
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .apply {
                // Also hear Bluetooth 5 extended adverts, which Windows 11 may use.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setLegacy(false)
                    setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                }
            }
            .build()
        runCatching { scanner.startScan(emptyList<ScanFilter>(), settings, scanCallback) }
            .onFailure { _state.value = LinkState.Failed; return }
        scanJob = scope.launch {
            delay(15_000)
            stopScan()
        }
    }

    /**
     * Computers this phone is paired with in Android's Bluetooth settings. A paired PC (for example
     * through Phone Link) often keeps a Bluetooth link to the phone open, and while it does, the
     * phone may not hear its Deckr advert. Connecting straight to its address still works.
     */
    fun pairedComputers(): List<FoundPc> = runCatching {
        adapter?.bondedDevices.orEmpty()
            .filter { it.bluetoothClass?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.COMPUTER }
            .map { FoundPc(it.address, it.name ?: "Paired PC", 0) }
    }.getOrDefault(emptyList())

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (_state.value == LinkState.Scanning) _state.value = LinkState.Idle
    }

    // ==================================================================== connecting

    fun connect(address: String, name: String, hello: ByteArray) {
        stopScan()
        disconnect()
        val device: BluetoothDevice = adapter?.getRemoteDevice(address) ?: run {
            _state.value = LinkState.Failed; return
        }
        this.hello = hello
        _connectedName.value = name
        _state.value = LinkState.Connecting
        drainChannels()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        sessionJob = scope.launch { runSession() }
    }

    fun disconnect() {
        sessionJob?.cancel()
        sessionJob = null
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        input = null
        status = null
        synchronized(lock) {
            queue.clear(); moveX = 0f; moveY = 0f; scrollV = 0f; scrollH = 0f; pad = null
        }
        if (_state.value !in setOf(LinkState.Denied, LinkState.Failed, LinkState.NotDeckr)) _state.value = LinkState.Idle
    }

    private fun drainChannels() {
        while (setupEvents.tryReceive().isSuccess) Unit
        writeDone.tryReceive(); readDone.tryReceive()
    }

    private suspend fun runSession() {
        // 1. Wait for the link, the bigger MTU and the service list.
        var notDeckr = false
        val ok = withTimeoutOrNull(12_000) {
            var result: Boolean? = null
            while (result == null) {
                result = when (setupEvents.receive()) {
                    "ready" -> true
                    "failed" -> false
                    "notdeckr" -> { notDeckr = true; false }
                    else -> null
                }
            }
            result
        } ?: false
        if (!ok) { fail(if (notDeckr) LinkState.NotDeckr else LinkState.Failed); return }

        // 2. Say who we are, then ask whether the PC lets us in. A PC that hasn't seen this phone
        //    before asks its owner first, so keep checking while it waits.
        if (!writeNow(hello, withResponse = true)) { fail(LinkState.Failed); return }
        val st = status
        val g = gatt
        if (st == null || g == null) { fail(LinkState.Failed); return }
        val askedAt = System.currentTimeMillis()
        while (true) {
            readDone.tryReceive()
            if (!g.readCharacteristic(st)) {
                if (gatt !== g) return // link went away
                delay(50)
                continue
            }
            val reply = withTimeoutOrNull(3_000) { readDone.receive() }
            when (reply?.firstOrNull()?.toInt()) {
                Protocol.STATUS_OK -> break
                Protocol.STATUS_WAITING -> {
                    _state.value = LinkState.Approval
                    if (System.currentTimeMillis() - askedAt > 120_000) { fail(LinkState.Failed); return }
                    delay(700)
                }
                Protocol.STATUS_DENIED -> { fail(LinkState.Denied); return }
                else -> { fail(LinkState.Failed); return }
            }
        }

        _state.value = LinkState.Connected

        // 3. Keep-alive pings.
        scope.launch {
            while (isActive && _state.value == LinkState.Connected) {
                delay(3_000)
                send(Protocol.ping())
            }
        }

        // 4. Writer loop.
        while (scope.isActive && _state.value == LinkState.Connected) {
            val packet = nextPacket()
            if (packet == null) {
                wake.receive()
                continue
            }
            if (!writeNow(packet, withResponse = false)) {
                fail(LinkState.Failed)
                return
            }
        }
    }

    private fun fail(state: LinkState) {
        _state.value = state
        sessionJob = null
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null
    }

    private fun nextPacket(): ByteArray? = synchronized(lock) {
        queue.poll()?.let { return it }
        val dx = moveX.toInt()
        val dy = moveY.toInt()
        if (dx != 0 || dy != 0) {
            moveX -= dx; moveY -= dy
            return Protocol.move(dx, dy)
        }
        val sv = scrollV.toInt()
        val sh = scrollH.toInt()
        if (sv != 0 || sh != 0) {
            scrollV -= sv; scrollH -= sh
            return Protocol.scroll(sv, sh)
        }
        pad?.let { pad = null; return Protocol.pad(it) }
        null
    }

    @Suppress("DEPRECATION")
    private suspend fun writeNow(data: ByteArray, withResponse: Boolean): Boolean {
        val g = gatt ?: return false
        val ch = input ?: return false
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        writeDone.tryReceive()
        repeat(20) { attempt ->
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, data, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                ch.writeType = type
                ch.value = data
                g.writeCharacteristic(ch)
            }
            if (started) {
                val result = withTimeoutOrNull(1_000) { writeDone.receive() }
                return result == BluetoothGatt.GATT_SUCCESS
            }
            // The stack was still busy; give it a moment.
            delay(if (attempt < 5) 2 else 10)
        }
        return false
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g != gatt) return
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (!g.requestMtu(185)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                setupEvents.trySend("failed")
                if (_state.value == LinkState.Connected || _state.value == LinkState.Connecting || _state.value == LinkState.Approval) {
                    val job = sessionJob
                    fail(LinkState.Failed)
                    job?.cancel()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) maxPacket = mtu - 3
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(Protocol.SERVICE)
            input = service?.getCharacteristic(Protocol.INPUT)
            this@DeckrConnection.status = service?.getCharacteristic(Protocol.STATUS)
            if (input == null || this@DeckrConnection.status == null) {
                setupEvents.trySend(if (status == BluetoothGatt.GATT_SUCCESS) "notdeckr" else "failed")
                return
            }
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            setupEvents.trySend("ready")
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDone.trySend(status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            readDone.trySend(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        @Deprecated("Used on Android 12 and older")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            readDone.trySend(if (status == BluetoothGatt.GATT_SUCCESS) c.value else null)
        }
    }

    // ==================================================================== public input API

    private val connected get() = _state.value == LinkState.Connected

    /** Queue a discrete packet. Pending movement is flushed first so order is kept. */
    fun send(packet: ByteArray) {
        if (!connected) return
        synchronized(lock) {
            val dx = moveX.toInt()
            val dy = moveY.toInt()
            if (dx != 0 || dy != 0) {
                queue.add(Protocol.move(dx, dy)); moveX -= dx; moveY -= dy
            }
            queue.add(packet)
        }
        wake.trySend(Unit)
    }

    fun move(dx: Float, dy: Float) {
        if (!connected) return
        synchronized(lock) { moveX += dx; moveY += dy }
        wake.trySend(Unit)
    }

    fun scroll(vertical: Float, horizontal: Float) {
        if (!connected) return
        synchronized(lock) { scrollV += vertical; scrollH += horizontal }
        wake.trySend(Unit)
    }

    fun click(button: Int) = send(Protocol.click(button))
    fun button(button: Int, down: Boolean) = send(Protocol.button(button, down))
    fun key(key: Protocol.Key) = send(Protocol.key(key))
    fun text(text: String) = Protocol.text(text, maxPacket).forEach(::send)

    fun pad(state: PadState) {
        if (!connected) return
        synchronized(lock) {
            // Never coalesce away a quick press: if the unsent state holds something that the new
            // state releases, send the unsent state first.
            val prev = pad
            if (prev != null) {
                val released = (prev.buttons and state.buttons.inv()) != 0 ||
                    (prev.dpad and state.dpad.inv()) != 0 ||
                    (prev.l2 > 0f && state.l2 == 0f) || (prev.r2 > 0f && state.r2 == 0f)
                if (released) queue.add(Protocol.pad(prev))
            }
            pad = state
        }
        wake.trySend(Unit)
    }

    fun close() {
        disconnect()
        stopScan()
    }
}
