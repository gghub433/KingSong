package app.gyro.protocol

/** Bytes to write to the wheel, sent [delayBeforeMs] after the previous frame of the same batch. */
class Outbound(val bytes: ByteArray, val delayBeforeMs: Long = 0) {
    override fun toString(): String = "Outbound(${Bytes.hex(bytes)}, +${delayBeforeMs}ms)"
}

/**
 * One-shot operational commands. These do not change persistent wheel configuration, so they do not
 * go through [SettingsWriteGuard].
 */
sealed interface WheelAction {
    data object Beep : WheelAction
    data class Light(val on: Boolean) : WheelAction
    data class Lock(val locked: Boolean) : WheelAction
}

class DecodeResult(
    val telemetryUpdated: Boolean,
    /** Frames the protocol must answer immediately (e.g. KingSong 0xA4 acknowledgement). */
    val outbound: List<Outbound> = emptyList(),
) {
    companion object {
        val NOTHING = DecodeResult(false)
    }
}

/**
 * Brand-neutral contract implemented by every wheel decoder. Implementations are single-threaded:
 * the BLE layer must call them from one coroutine.
 *
 * Adding a brand means adding one implementation and one entry in [ProtocolFactory]; the rest of
 * the app only sees [WheelState].
 */
interface WheelProtocol {
    val family: ProtocolFamily
    val state: WheelState

    /** Frames to send right after notifications are enabled. */
    fun onConnected(nowMs: Long): List<Outbound>

    /** Feed one BLE notification (any chunking). */
    fun decode(chunk: ByteArray, nowMs: Long): DecodeResult

    /** Called every ~25 ms; protocols that must poll the wheel return their requests here. */
    fun poll(nowMs: Long): List<Outbound> = emptyList()

    /** Returns null when this wheel does not support the action. */
    fun encode(action: WheelAction): List<Outbound>?

    /** Parameters this wheel can write. */
    val writableParams: Set<WheelParam> get() = emptySet()

    /**
     * Encodes a settings write. A [SettingsWritePermit] can only be obtained from
     * [SettingsWriteGuard.authorize], which enforces the factory backup, the battery floor and the
     * confirmation of every changed value.
     */
    fun encodeSettings(permit: SettingsWritePermit): List<Outbound>? = null

    /** Overrides the series cell count (the rider knows their battery better than a guess). */
    fun setCellsOverride(cells: Int?) {}
}

object ProtocolFactory {
    fun create(family: ProtocolFamily, bleName: String?): WheelProtocol = when (family) {
        ProtocolFamily.KINGSONG -> KingSongProtocol(bleName)
        ProtocolFamily.BEGODE -> BegodeProtocol(bleName)
        ProtocolFamily.VETERAN -> VeteranProtocol(bleName)
        ProtocolFamily.INMOTION_V1 -> InMotionV1Protocol(bleName)
        ProtocolFamily.INMOTION_V2 -> InMotionV2Protocol(bleName)
        ProtocolFamily.NINEBOT_Z -> NinebotZProtocol(bleName)
    }
}

/** Shared state handling for decoders. */
abstract class BaseProtocol(final override val family: ProtocolFamily, bleName: String?) : WheelProtocol {

    protected var info: WheelInfo = WheelInfo(family = family, name = bleName)
    protected var telemetry: Telemetry = Telemetry()
    protected val bmsPacks: Array<BmsPack?> = arrayOfNulls(2)
    protected val settings: MutableMap<WheelParam, Int> = linkedMapOf()
    protected var alerts: Set<WheelAlert> = emptySet()
    private var hasTelemetry = false
    private var cellsOverride: Int? = null

    override val state: WheelState
        get() = WheelState(
            info = info,
            telemetry = telemetry,
            bms = bmsPacks.filterNotNull(),
            settings = settings.toMap(),
            alerts = alerts,
            hasTelemetry = hasTelemetry,
        )

    override fun setCellsOverride(cells: Int?) {
        cellsOverride = cells
        info = if (cells != null) {
            info.copy(cellsSeries = cells, cellsSource = CellsSource.USER)
        } else {
            info.copy(cellsSeries = null, cellsSource = null)
        }
    }

    /** Resolves the series cell count, never replacing a better source with a worse one. */
    protected fun updateCells(cells: Int?, source: CellsSource) {
        if (cells == null || cells <= 0) return
        if (cellsOverride != null) return
        val current = info.cellsSource
        if (current == null || source.ordinal < current.ordinal ||
            (source == current && source != CellsSource.VOLTAGE_GUESS)
        ) {
            if (info.cellsSeries != cells || info.cellsSource != source) {
                info = info.copy(cellsSeries = cells, cellsSource = source)
            }
        }
    }

    /**
     * Guesses cells from voltage only while nothing better is known. The first guess sticks: a sagging
     * pack would otherwise flip a 24S wheel to 20S mid-ride.
     */
    protected fun guessCellsFromVoltage(voltage: Double) {
        if (info.cellsSource != null) return
        BatteryEstimator.guessCells(voltage)?.let { updateCells(it, CellsSource.VOLTAGE_GUESS) }
    }

    protected fun estimatedBatteryPercent(voltage: Double): Double? {
        val cells = info.cellsSeries ?: return null
        return BatteryEstimator.percentFromPackVoltage(voltage, cells)
    }

    protected fun publish(sample: Telemetry) {
        telemetry = sample
        hasTelemetry = true
    }

    protected fun bms(index: Int): BmsPack = bmsPacks[index] ?: BmsPack(index = index + 1)

    protected fun setBms(index: Int, pack: BmsPack) {
        bmsPacks[index] = pack
    }

    protected fun addCapabilities(vararg caps: Capability) {
        val merged = info.capabilities + caps
        if (merged != info.capabilities) info = info.copy(capabilities = merged)
    }

    /**
     * Battery current when the wheel only reports motor phase current: I_batt ≈ duty × I_phase.
     * The sign follows the direction of travel so braking shows up as negative (regeneration).
     */
    protected fun batteryCurrentFromPhase(phaseA: Double, pwmPercent: Double?, speedKmh: Double): Double? {
        val duty = pwmPercent?.let { kotlin.math.abs(it) / 100.0 } ?: return null
        val motoring = if (speedKmh < 0) -phaseA else phaseA
        return duty * motoring
    }
}
