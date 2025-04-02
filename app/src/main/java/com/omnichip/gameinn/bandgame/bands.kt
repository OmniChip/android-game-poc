package com.omnichip.gameinn.bandgame

import android.app.Activity
import android.bluetooth.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.Time
import java.sql.Timestamp
import java.util.*
import kotlin.random.Random

val BAND_INERTIA_SERVICE_UUID: UUID = UUID.fromString("00001833-0000-1000-8000-00805f9b34fb")
val BAND_MOTOR_SERVICE_UUID: UUID = UUID.fromString("00001844-0000-1000-8000-00805f9b34fb")
val DIS_SERVICE_UUID: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
val TIME_SERVICE_UUID: UUID = UUID.fromString("00001805-0000-1000-8000-00805f9b34fb")
val BAND_INERTIA_DATA_UUID: UUID = UUID.fromString("000029fe-0000-1000-8000-00805f9b34fb")
val BAND_INERTIA_ZERO_UUID: UUID = UUID.fromString("000029fd-0000-1000-8000-00805f9b34fb")
val BAND_VIBE_CTL_UUID: UUID = UUID.fromString("000029f0-0000-1000-8000-00805f9b34fb")
val BAND_TIMESYNC_GROUP_UUID: UUID = UUID.fromString("000029fa-0000-1000-8000-00805f9b34fb")
val BAND_TIMESYNC_STEPS_UUID: UUID = UUID.fromString("000029fb-0000-1000-8000-00805f9b34fb")
val BAND_TIMESYNC_MODE_UUID: UUID = UUID.fromString("000029fc-0000-1000-8000-00805f9b34fb")
val DIS_MFG_NAME_UUID: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
val DIS_PNP_ID_UUID: UUID = UUID.fromString("00002a50-0000-1000-8000-00805f9b34fb")
val GATT_CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

typealias BandCallback = (BandDevice) -> Unit
typealias BandDataCallback = (BandDevice, BandData) -> Unit

private fun ubyte(v: Byte): Int = if (v < 0) v.toInt() + 0x100 else v.toInt()

private fun getLeShort(data: ByteArray, offset: Int): Int
{
    return ubyte(data[offset]) + data[offset + 1] * 0x100
}

private fun getLeInt(data: ByteArray, offset: Int): Int
{
    return ubyte(data[offset]) + ubyte(data[offset + 1]) * 0x100 + ubyte(data[offset + 2]) * 0x10000 + data[offset + 3].toInt() * 0x1000000
}

class BandData {
    val ax: Int
    val ay: Int
    val az: Int
    val gx: Int
    val gy: Int
    val gz: Int
    val timestamp: Long

    constructor() {
        timestamp = 0L
        gx = 0
        gy = 0
        gz = 0
        ax = 0
        ay = 0
        az = 0
    }

    constructor(data: ByteArray) {
        timestamp = getLeInt(data, 0).toLong()
        gx = getLeShort(data, 4)
        gy = getLeShort(data, 6)
        gz = getLeShort(data, 8)
        ax = getLeShort(data, 10)
        ay = getLeShort(data, 12)
        az = getLeShort(data, 14)
    }

    val axis_data: ByteArray get() {
        val buf = ByteBuffer.allocate(6 * 2)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(gx.toShort())
        buf.putShort(gy.toShort())
        buf.putShort(gz.toShort())
        buf.putShort(ax.toShort())
        buf.putShort(ay.toShort())
        buf.putShort(az.toShort())
        return buf.array()
    }
}

data class GestureData(val timestamp: Long, val type: Int, val x: Float, val y: Float, val z: Float)

class BandCalibrator {
    private var handle: Long = 0

    private external fun create()
    private external fun destroy()
    private external fun process(data: BandData): Boolean
    private external fun getZeroOffset(): BandData
    private external fun isDone(): Boolean
    private external fun getCalibrationNeededMask(): Byte
    private external fun getStatusString(): String

    val active: Boolean
        get() = handle != 0L
    val zero_offset: BandData
        get() = if (active) getZeroOffset() else BandData()
    val done: Boolean
        get() = if (active) isDone() else true
    val calibration_needed_mask: Int
        get() = if (active) getCalibrationNeededMask().toInt() else 0
    val status: String
        get() = if (active) if (done) "DONE" else getStatusString() else ""

    fun processData(data: BandData): Boolean = if (active) process(data) else false
    fun start() { if (!active && BandManager.can_calibrate) create() }
    fun stop() { if (active) destroy() }
}

class BandDetector {
    private var handle: Long = 0

    private external fun create()
    private external fun destroy()
    private external fun getGestures(band_mask: Int): Long
    private external fun enableGestures(mask: Long): Long
    private external fun process(which: Int, data: BandData): Array<GestureData>
    private external fun reset()

    init {
        create()
    }

    fun cleanup() {
        destroy()
    }

    var gestures: Long = 0L
        set(value) { enableGestures(value); field = value; }

    fun getAvailableGestures(band_mask: Int) = getGestures(band_mask)
    fun processData(which: Int, data: BandData): Array<GestureData> = process(which, data)
    fun resetDetection() = reset()

    companion object {
        const val POINT_LEFT_HAND_DOWN = 1
        const val POINT_LEFT_LEG_DOWN = 16
        const val SQUAT_GESTURE = 25
        const val STEERING_WHEEL = 26
        const val PUNCH = 29

        const val POINT_GESTURE_MASK = 0x1fffffeL   // all POINT_* gestures
        const val POINT_ANY_MASK = 0x1c0f800L
        const val SQUAT_GESTURE_MASK = 1L shl SQUAT_GESTURE
        const val STEERING_WHEEL_MASK = 1L shl STEERING_WHEEL
        const val PUNCHES_MASK = 0x111L shl PUNCH
        const val CONTINUOUS_GESTURES_MASK = STEERING_WHEEL_MASK or PUNCHES_MASK
        const val ALL_GESTURES_MASK = POINT_GESTURE_MASK or SQUAT_GESTURE_MASK

        const val POINT_LEFT_MASK = 0x7003eL    // POINT_*L_*
        const val POINT_RIGHT_MASK = 0x3807c0L  // POINT_*R_*
        const val POINT_ARM_MASK = 0xfffeL      // POINT_*ARM_*
        const val POINT_LEG_MASK = 0x1ff0000L   // POINT_*LEG_*

        val gestureName = mapOf(
            1 to "left hand down",
            2 to "left hand low",
            3 to "left hand level",
            4 to "left hand high",
            5 to "left hand up",
            6 to "right hand down",
            7 to "right hand low",
            8 to "right hand level",
            9 to "right hand high",
            10 to "right hand up",
            11 to "hand down",
            12 to "hand low",
            13 to "hand level",
            14 to "hand high",
            15 to "hand up",
            16 to "left leg down",
            17 to "left leg low",
            18 to "left leg level",
            19 to "right leg down",
            20 to "right leg low",
            21 to "right leg level",
            22 to "leg down",
            23 to "leg low",
            24 to "leg level",
            25 to "squat",
            26 to "steering wheel",
            27 to "guard up",
            28 to "guard down",
            29 to "punch",
            30 to "punch low",
            31 to "punch straight",
            32 to "punch high",
            33 to "left punch",
            34 to "left punch low",
            35 to "left punch straight",
            36 to "left punch high",
            37 to "right punch",
            38 to "right punch low",
            39 to "right punch straight",
            40 to "right punch high"
        )
    }
}

class TimesyncState(tsync_sv: BluetoothGattService) {
    val ts_group = tsync_sv.getCharacteristic(BAND_TIMESYNC_GROUP_UUID)
    val ts_mode = tsync_sv.getCharacteristic(BAND_TIMESYNC_MODE_UUID)
    val ts_steps = tsync_sv.getCharacteristic(BAND_TIMESYNC_STEPS_UUID)
    val supported: Boolean
        get() = ts_group != null && ts_mode != null && ts_steps != null
                && ts_steps.getDescriptor(GATT_CCCD_UUID) != null
    var state = TSS_INITIAL
    var counter: Byte = 0
    var seq = 0

    companion object {
        const val TIMESYNC_MASTER = 2.toByte()
        const val TIMESYNC_SLAVE = 1.toByte()
        const val TIMESYNC_DISABLED = 0.toByte()

        const val TSS_INITIAL = 0
        const val TSS_STEP_NOTIFY_ON = 1
        const val TSS_MODE_DISABLED = 2
        const val TSS_GOT_COUNTER = 3
        const val TSS_GROUP_SET = 4
        const val TSS_MODE_SLAVE = 5
        const val TSS_SYNCED = 6
        const val TSS_MODE_MASTER = 7
    }

    fun wrapCb(cb: (Int)->Unit): (Int)->Unit {
        val s = ++seq
        return { status: Int -> if (s == seq) cb(status) }
    }

    fun makeModeRequest(mode: Byte?, cb: (Int)->Unit): BluetoothGattRequest =
        BluetoothGattRequest(ts_mode!!, if (mode == null) null else ByteArray(1) { mode }, wrapCb(cb))

    fun makeGroupRequest(group: ByteArray?, cb: (Int)->Unit): BluetoothGattRequest =
        BluetoothGattRequest(ts_group!!, group, wrapCb(cb))

    fun makeTimeSteppedGetRequest(cb: (Int)->Unit): BluetoothGattRequest =
        BluetoothGattRequest(ts_steps!!, null, wrapCb(cb))

    fun updateTimeStepped() =
        ts_steps.value?.let {
            if (it.size > 0)
                counter = it[0]
        }
}

class BandDevice(val manager: BandManager, dev: BluetoothDevice) : BluetoothDeviceBase() {
    var on_new_data: BandDataCallback? = null
    var on_calibration_change: BandCallback? = null
    var cb_data: Any? = null

    var listening: Boolean = false
        private set

    var association: Int = 0

    private val calibrator = BandCalibrator()
    private var calib_available = true
    private var calib_write_in_progress = false
    private var calib_state = 0

    val inertiaCalibrationStatus: String
        get() = if (!calib_available)
            "N/A"
        else if (calib_write_in_progress)
            "PENDING"
        else if (!calibrator.active)
            "OK"
        else
            calibrator.status
    val timesyncStatus: String
        get() = timesync?.state.let { when (it) {
            null -> "unavailable"
            TimesyncState.TSS_MODE_MASTER -> "master"
            TimesyncState.TSS_SYNCED -> "synced"
            else -> it.toString()
        } }
    val calibrationStatus: String
        get() = "TS:" + timesyncStatus + "  I:" + inertiaCalibrationStatus

    val identity: String
        get() = dev.name + " (" + dev.address + ")"

    override fun onCharacteristicUpdate(characteristic: BluetoothGattCharacteristic, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS)
            return
        if (characteristic.uuid.equals(BAND_INERTIA_DATA_UUID))
            updateSensorData(characteristic.value)
        else if (characteristic.uuid.equals(BAND_TIMESYNC_STEPS_UUID))
            continueTimesync(TimesyncState.TSS_SYNCED, status)
    }

    private var vibe_char: BluetoothGattCharacteristic? = null
    internal var timesync: TimesyncState? = null
    val can_vibe: Boolean
        get() = vibe_char != null

    private fun checkServices(): Boolean {
        val sensor_sv = gatt.getService(BAND_INERTIA_SERVICE_UUID)
        if (sensor_sv == null) {
            util.log("inertia service not found")
            return false
        }

        val data_char = sensor_sv.getCharacteristic(BAND_INERTIA_DATA_UUID)
        if (data_char == null) {
            util.log("inertia data characteristic not found")
            return false
        }

        if (data_char.getDescriptor(GATT_CCCD_UUID) == null) {
            util.log("inertia data CCCD not found")
            return false
        }

        if (gatt.getService(TIME_SERVICE_UUID) == null) {
            util.log("timesync service not found")
            return false
        }

        val tsync_sv = gatt.getService(TIME_SERVICE_UUID)
        if (tsync_sv == null) {
            util.log("timesync service not found")
            return false
        }

        val ts = TimesyncState(tsync_sv)
        if (!ts.supported) {
            util.log("timesync service incomplete")
            return false
        }
        timesync = ts

        vibe_char = gatt.getService(BAND_MOTOR_SERVICE_UUID)?.getCharacteristic(BAND_VIBE_CTL_UUID)?.apply {
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        util.log("%s vibe control service", if (can_vibe) "found" else "did not find")

        val calib_char: BluetoothGattCharacteristic? = sensor_sv.getCharacteristic(BAND_INERTIA_ZERO_UUID)
        queueRead(calib_char) { status: Int ->
            val calib_data =
                if (status == BluetoothGatt.GATT_SUCCESS) calib_char!!.value else null
            manager.loop.post {
                setupCalibration(calib_data)
            }
        }

        if (timesync?.state == TimesyncState.TSS_INITIAL)
            restartTimesync()

        if (listening)
            setInertiaDataNotify(true)
        return true
    }

    private fun continueTimesync(state: Int, @Suppress("UNUSED_PARAMETER") result: Int) {
        // TODO: check result
        val ts = timesync
        if (ts == null)
            return
        if (state == TimesyncState.TSS_SYNCED && ts.state != TimesyncState.TSS_MODE_SLAVE)
            return
        ts.state = state

        util.log("TimeSync moving to state %d", state)
        on_calibration_change?.invoke(this)

        if (state == TimesyncState.TSS_INITIAL) {
            setNotify(ts.ts_steps, true, ts.wrapCb {
                continueTimesync(TimesyncState.TSS_STEP_NOTIFY_ON, it)
            })
            return
        }

        val req: BluetoothGattRequest?
        when (state) {
            TimesyncState.TSS_STEP_NOTIFY_ON ->
                req = ts.makeModeRequest(TimesyncState.TIMESYNC_DISABLED) { continueTimesync(TimesyncState.TSS_MODE_DISABLED, it) }
            TimesyncState.TSS_MODE_DISABLED ->
                req = ts.makeTimeSteppedGetRequest() { ts.updateTimeStepped(); continueTimesync(TimesyncState.TSS_GOT_COUNTER, it) }
            TimesyncState.TSS_GOT_COUNTER ->
                req = ts.makeGroupRequest(manager.timesync_group) { continueTimesync(TimesyncState.TSS_GROUP_SET, it) }
            TimesyncState.TSS_GROUP_SET -> synchronized(manager) {
                if (manager.timingMaster != null) {
                    req = ts.makeModeRequest(TimesyncState.TIMESYNC_SLAVE) { continueTimesync(TimesyncState.TSS_MODE_SLAVE, it) }
                } else {
                    manager.timingMaster = this
                    req = ts.makeModeRequest(TimesyncState.TIMESYNC_MASTER) { continueTimesync(TimesyncState.TSS_MODE_MASTER, it) }
                }
            }
            else ->
                req = null
        }
        req?.also { queueRequest(it) }
    }

    internal fun restartTimesync() {
        val ts = timesync
        if (ts == null)
            return

        val restart_state: Int
        if (ts.state == TimesyncState.TSS_INITIAL)
            restart_state = TimesyncState.TSS_INITIAL
        else
            restart_state = TimesyncState.TSS_STEP_NOTIFY_ON

        continueTimesync(restart_state, BluetoothGatt.GATT_SUCCESS)
    }

    override fun onGattConnected(state: Boolean) {
        manager.loop.post {
            if (state && checkServices())
                manager.addBand(this)
            else
                manager.removeBand(this)
        }
    }

    private fun calibWriteDone(zero_char: BluetoothGattCharacteristic, zero_data: ByteArray, status: Int ) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            calib_write_in_progress = false
            setupCalibration(zero_data)
        } else {
            queueWrite(zero_char, zero_data) { calibWriteDone(zero_char, zero_data, it) }
        }
    }

    private fun setZeroOffset(data: ByteArray?) {
        val zero_char = gatt.getService(BAND_INERTIA_SERVICE_UUID).getCharacteristic(BAND_INERTIA_ZERO_UUID)!!
        val zero_data: ByteArray = data ?: ByteArray(12) { 0.toByte() }

        calib_write_in_progress = true
        queueWrite(zero_char, zero_data) { calibWriteDone(zero_char, zero_data, it) }
    }

    private fun processCalibration(values: BandData) {
        if (calibrator.processData(values)) {
            val zdata = calibrator.zero_offset.axis_data
            calibrator.stop()
            on_calibration_change?.invoke(this)
            setZeroOffset(zdata)
        } else {
            val mask = calibrator.calibration_needed_mask
            if (calib_state != mask) {
                calib_state = mask
                on_calibration_change?.invoke(this)
            }
        }
    }

    private fun updateSensorData(data: ByteArray) {
        val values = BandData(data)

        if (calibrator.active)
            processCalibration(values)

        on_new_data?.invoke(this, values)
    }

    private fun setupCalibration(data: ByteArray?) {
        val isZero = data?.all { it == 0.toByte() } ?: false
        if (isZero != calibrator.active) {
            if (calibrator.active) {
                calibrator.stop()
            } else {
                calibrator.start()
                calib_state = calibrator.calibration_needed_mask
            }
        }
        calib_available = data != null
        on_calibration_change?.invoke(this)
    }

    public fun resetCalibration() {
        if (calibrator.active)
            calibrator.stop()
        on_calibration_change?.invoke(this)
        setZeroOffset(null)
    }

    private fun setInertiaDataNotify(enable: Boolean) {
        setNotify(gatt.getService(BAND_INERTIA_SERVICE_UUID).getCharacteristic(BAND_INERTIA_DATA_UUID), enable)
    }

    fun startListening() {
        if (listening)
            return

        if (!gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH))
            util.log("can't set connection priority to HIGH")
        setInertiaDataNotify(true)
        listening = true
    }

    fun stopListening() {
        if (!listening)
            return

        setInertiaDataNotify(false)
        if (!gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER))
            util.log("can't set connection priority to LOW_POWER")
        listening = false
    }

    fun triggerVibe(vibe_array: ByteArray) {
        if (vibe_array.size == 0)
            return
        if (vibe_array.size > 8)
            throw IllegalArgumentException("vibe_array to big")  //FIXME: check char limits or queue
        vibe_char?.apply {
            queueWrite(this, vibe_array)
        }
    }

    fun cleanup() {
        stopListening()
        calibrator.stop()
    }

    init {
        lateInit(manager.ctx, dev) { manager.util.log(it) }
    }
}

class BandManager : BluetoothDeviceManagerBase() {
    val bands
        get() = devices.mapNotNull { it.second as? BandDevice }
    var on_band_found: BandCallback? = null
    var on_band_lost: BandCallback? = null
    internal var timesync_group = Random.Default.nextBytes(5)
    internal var timingMaster: BandDevice? = null

    override fun onNewDevice(dev: BluetoothDevice): BluetoothDeviceBase? {
        return BandDevice(this, dev)
    }

    fun addBand(band: BandDevice) {
        util.log("New device: %s - %s", band.dev.address, band.dev.name)
        on_band_found?.invoke(band)
    }

    fun removeBand(band: BandDevice) {
        val ts_master_lost = band === timingMaster
        val ts_note = if (ts_master_lost) " (was timing master)" else ""
        util.log("Device lost: %s - %s%s", band.dev.address, band.dev.name, ts_note)
        if (ts_master_lost)
            timingMaster = null
        band.cleanup()
        on_band_lost?.invoke(band)
        if (ts_master_lost) {
            var did_one = false
            for (b in bands) {
                b.restartTimesync()
                did_one = true
            }
            if (did_one)
                timesync_group = Random.Default.nextBytes(5)
        }
    }

    override fun initialize(ctx: Activity, requestCodeBT: Int): Unit {
        super.initialize(ctx, requestCodeBT)
        startScanning(listOf(BAND_INERTIA_SERVICE_UUID))
        util.log("Initialized")
    }

    companion object {
        val can_calibrate =
            try {
                System.loadLibrary("andsupport")
                true
            } catch (e: UnsatisfiedLinkError) {
                false
            }
    }
}

