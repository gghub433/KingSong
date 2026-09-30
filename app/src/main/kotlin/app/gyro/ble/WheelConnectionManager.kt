package app.gyro.ble

import android.Manifest
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import app.gyro.data.db.KnownWheelDao
import app.gyro.data.db.KnownWheelEntity
import app.gyro.protocol.BleTransport
import app.gyro.protocol.Outbound
import app.gyro.protocol.ProtocolDetector
import app.gyro.protocol.ProtocolFactory
import app.gyro.protocol.ProtocolFamily
import app.gyro.protocol.SettingsWritePermit
import app.gyro.protocol.WheelAction
import app.gyro.protocol.WheelParam
import app.gyro.protocol.WheelProtocol
import app.gyro.protocol.WheelState
import app.gyro.protocol.uuid16
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.math.min

data class WheelTarget(val address: String, val name: String?, val family: ProtocolFamily? = null)

sealed interface ConnectionState {
    val target: WheelTarget?

    data object Idle : ConnectionState {
        override val target: WheelTarget? = null
    }

    data class Connecting(override val target: WheelTarget, val attempt: Int) : ConnectionState
    data class Identifying(override val target: WheelTarget) : ConnectionState
    data class Connected(override val target: WheelTarget, val family: ProtocolFamily) : ConnectionState
    data class Reconnecting(override val target: WheelTarget, val attempt: Int, val reason: String) : ConnectionState
    data class Failed(override val target: WheelTarget?, val reason: String) : ConnectionState
}

enum class SendResult { SENT, UNSUPPORTED, NOT_CONNECTED }

/** GATT layout found on the device, before the wire protocol is known. */
data class TransportLayout(
    val transport: BleTransport,
    val hasNinebotService: Boolean = false,
    val hasInMotionMarker: Boolean = false,
) {
    companion object {
        fun identify(services: List<BluetoothGattService>): TransportLayout? {
            fun service(short: Int) = services.firstOrNull { it.uuid == uuid16(short) }
            val nordic = services.firstOrNull { it.uuid == BleTransport.NORDIC_UART.notifyService }
            if (nordic != null) {
                return TransportLayout(
                    transport = BleTransport.NORDIC_UART,
                    hasNinebotService = service(0xFEE7) != null,
                    // InMotion V2 boards expose "Central Address Resolution"; Ninebot Z does not.
                    hasInMotionMarker = service(0x1800)?.getCharacteristic(uuid16(0x2AA6)) != null,
                )
            }
            val ffe0 = service(0xFFE0)
            if (ffe0?.getCharacteristic(uuid16(0xFFE4)) != null && service(0xFFE5) != null) {
                return TransportLayout(BleTransport.INMOTION_V1)
            }
            if (ffe0?.getCharacteristic(uuid16(0xFFE1)) != null) return TransportLayout(BleTransport.HM10_UART)
            return null
        }
    }
}

/**
 * Owns the BLE link to one wheel: connects, identifies the protocol, feeds notifications to the
 * decoder, polls, writes commands and reconnects with backoff when the link drops.
 *
 * All decoder calls run on [protocolContext] (one thread), as [WheelProtocol] requires.
 */
class WheelConnectionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val knownWheels: KnownWheelDao,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _wheel = MutableStateFlow<WheelState?>(null)
    val wheel: StateFlow<WheelState?> = _wheel.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val protocolContext = Dispatchers.Default.limitedParallelism(1)

    private var session: Job? = null

    @Volatile private var protocol: WheelProtocol? = null

    @Volatile private var outbox: SendChannel<Outbound>? = null

    private sealed interface Outcome {
        data class Retry(val reason: String, val detected: ProtocolFamily? = null) : Outcome
        data class Fatal(val reason: String) : Outcome
    }

    fun connect(target: WheelTarget) {
        val previous = session
        _wheel.value = null
        session = scope.launch {
            previous?.cancelAndJoin()
            runSession(target)
        }
    }

    fun disconnect() {
        session?.cancel()
        session = null
        protocol = null
        outbox = null
        _state.value = ConnectionState.Idle
    }

    suspend fun send(action: WheelAction): SendResult {
        val p = protocol ?: return SendResult.NOT_CONNECTED
        val box = outbox ?: return SendResult.NOT_CONNECTED
        val frames = withContext(protocolContext) { p.encode(action) } ?: return SendResult.UNSUPPORTED
        frames.forEach { box.send(it) }
        return SendResult.SENT
    }

    /** Only reachable with a permit from [app.gyro.protocol.SettingsWriteGuard]. */
    suspend fun writeSettings(permit: SettingsWritePermit): SendResult {
        val p = protocol ?: return SendResult.NOT_CONNECTED
        val box = outbox ?: return SendResult.NOT_CONNECTED
        val frames = withContext(protocolContext) { p.encodeSettings(permit) } ?: return SendResult.UNSUPPORTED
        frames.forEach { box.send(it) }
        return SendResult.SENT
    }

    fun writableParams(): Set<WheelParam> = protocol?.writableParams.orEmpty()

    suspend fun setCellsOverride(cells: Int?) {
        val p = protocol ?: return
        val state = withContext(protocolContext) {
            p.setCellsOverride(cells)
            p.state
        }
        _wheel.value = state
    }

    private suspend fun runSession(initial: WheelTarget) {
        var target = initial
        var attempt = 0
        while (true) {
            val outcome = try {
                connectOnce(target, attempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome.Retry(e.message ?: "Ошибка Bluetooth")
            }
            when (outcome) {
                is Outcome.Fatal -> {
                    _state.value = ConnectionState.Failed(target, outcome.reason)
                    return
                }
                is Outcome.Retry -> {
                    attempt++
                    outcome.detected?.let { target = target.copy(family = it) }
                    _state.value = ConnectionState.Reconnecting(target, attempt, outcome.reason)
                    delay(min(15_000L, 1_000L shl min(attempt - 1, 4)))
                }
            }
        }
    }

    private suspend fun connectOnce(target: WheelTarget, attempt: Int): Outcome {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return Outcome.Fatal("Bluetooth недоступен на этом устройстве")
        if (!hasConnectPermission()) return Outcome.Fatal("Нет разрешения на подключение по Bluetooth")
        if (!adapter.isEnabled) return Outcome.Retry("Bluetooth выключен")
        val device = runCatching { adapter.getRemoteDevice(target.address) }.getOrNull()
            ?: return Outcome.Fatal("Некорректный адрес устройства")

        _state.value = ConnectionState.Connecting(target, attempt)
        val client = GattClient(context, device)
        try {
            if (!client.connect()) return Outcome.Retry("Колесо не отвечает")
            val layout = TransportLayout.identify(client.discoverServices())
                ?: return Outcome.Fatal("Устройство не похоже на моноколесо: нет последовательного BLE-сервиса")
            client.requestMtu(517)
            client.requestHighPriority()
            val transport = layout.transport
            if (!client.enableNotifications(transport.notifyService, transport.notifyCharacteristic)) {
                return Outcome.Retry("Не удалось подписаться на данные колеса")
            }

            val replay = mutableListOf<ByteArray>()
            val known = knownWheels.get(target.address)
            val remembered = known?.family?.let { name -> ProtocolFamily.entries.firstOrNull { it.name == name } }
            val family = target.family
                ?: remembered?.takeIf { it.transport == transport }
                ?: run {
                    _state.value = ConnectionState.Identifying(target)
                    detect(layout, target, client, replay)
                }
                ?: return Outcome.Fatal("Не удалось определить протокол колеса. Выберите марку вручную в списке устройств.")

            val p = withContext(protocolContext) {
                ProtocolFactory.create(family, target.name).also { proto ->
                    known?.cellsOverride?.let { proto.setCellsOverride(it) }
                }
            }
            val box = Channel<Outbound>(Channel.UNLIMITED)
            protocol = p
            outbox = box
            remember(target, family, known)
            _state.value = ConnectionState.Connected(target.copy(family = family), family)

            return coroutineScope {
                try {
                    launch {
                        for (o in box) {
                            if (o.delayBeforeMs > 0) delay(o.delayBeforeMs)
                            writeChunks(client, transport, o.bytes)
                        }
                    }
                    withContext(protocolContext) { p.onConnected(now()) }.forEach { box.send(it) }
                    for (chunk in replay) decodeChunk(p, chunk, box)
                    launch { for (n in client.incoming) decodeChunk(p, n.value, box) }
                    launch {
                        while (isActive) {
                            delay(POLL_INTERVAL_MS)
                            withContext(protocolContext) { p.poll(now()) }.forEach { box.send(it) }
                        }
                    }
                    client.connected.first { !it }
                    Outcome.Retry("Связь с колесом потеряна", detected = family)
                } finally {
                    coroutineContext.cancelChildren()
                }
            }
        } finally {
            protocol = null
            outbox = null
            client.close()
        }
    }

    private suspend fun decodeChunk(p: WheelProtocol, chunk: ByteArray, box: SendChannel<Outbound>) {
        val (result, state) = withContext(protocolContext) {
            val r = p.decode(chunk, now())
            r to p.state
        }
        _wheel.value = state
        result.outbound.forEach { box.send(it) }
    }

    /**
     * HM-10 wheels stream on their own, so their bytes are sniffed without sending anything (an
     * unknown byte could be a settings command for another brand). Nordic UART wheels only answer
     * requests, so each candidate protocol is probed with its harmless identification request.
     */
    private suspend fun detect(
        layout: TransportLayout,
        target: WheelTarget,
        client: GattClient,
        replay: MutableList<ByteArray>,
    ): ProtocolFamily? {
        val incoming: ReceiveChannel<GattClient.Notification> = client.incoming
        return when (layout.transport) {
            BleTransport.INMOTION_V1 -> ProtocolFamily.INMOTION_V1
            BleTransport.HM10_UART -> {
                val hint = ProtocolDetector.hintFromName(target.name)
                if (hint == ProtocolFamily.KINGSONG) return hint
                val sniffer = ProtocolDetector.StreamSniffer()
                var found: ProtocolFamily? = null
                withTimeoutOrNull(4_000) {
                    while (found == null) {
                        val chunk = incoming.receive().value
                        replay += chunk
                        sniffer.feed(chunk)
                        found = sniffer.result()
                    }
                }
                found ?: sniffer.result(minFrames = 1)
                    ?: hint?.takeIf { it.transport == BleTransport.HM10_UART }
            }
            BleTransport.NORDIC_UART -> {
                val candidates = ProtocolDetector.candidates(
                    BleTransport.NORDIC_UART, target.name, layout.hasNinebotService, layout.hasInMotionMarker,
                )
                for (family in candidates) {
                    val probe = ProtocolFactory.create(family, target.name).onConnected(now())
                    probe.forEach { writeChunks(client, layout.transport, it.bytes) }
                    val bytes = ByteArrayOutputStream()
                    var reply: ProtocolFamily? = null
                    withTimeoutOrNull(1_500) {
                        while (reply == null) {
                            val chunk = incoming.receive().value
                            replay += chunk
                            bytes.write(chunk)
                            reply = ProtocolDetector.nordicReply(bytes.toByteArray())
                        }
                    }
                    if (reply != null) return reply
                }
                null
            }
        }
    }

    private suspend fun writeChunks(client: GattClient, transport: BleTransport, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val end = min(bytes.size, offset + CHUNK_SIZE)
            client.write(transport.writeService, transport.writeCharacteristic, bytes.copyOfRange(offset, end))
            offset = end
            if (offset < bytes.size && transport.chunkDelayMs > 0) delay(transport.chunkDelayMs)
        }
    }

    private suspend fun remember(target: WheelTarget, family: ProtocolFamily, old: KnownWheelEntity?) {
        knownWheels.upsert(
            KnownWheelEntity(
                address = target.address,
                name = target.name ?: old?.name,
                brand = old?.brand,
                model = old?.model,
                serial = old?.serial,
                family = family.name,
                cellsOverride = old?.cellsOverride,
                capacityWhOverride = old?.capacityWhOverride,
                lastConnectedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun now(): Long = SystemClock.elapsedRealtime()

    private companion object {
        const val POLL_INTERVAL_MS = 25L

        /** Many HM-10 clones drop writes longer than the default 20-byte payload, whatever the MTU. */
        const val CHUNK_SIZE = 20
    }
}
