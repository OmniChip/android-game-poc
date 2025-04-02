package com.omnichip.gameinn.bandgame

import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.ParcelUuid
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

fun getScanErrorString(value: Int): String = when (value) {
    ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
    ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "APP_REG_FAILED"
    ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
    ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
    else -> String.format("<ScanFailed:%d>", value)
}

class BluetoothGattRequest {
    private val on_execute: (BluetoothGatt, LogUtil)->Unit
    private val on_complete: ((Int)->Unit)?

    private fun name_of(descriptor: BluetoothGattDescriptor): String =
        BluetoothDeviceBase.full_name_of(descriptor)
    private fun name_of(characteristic: BluetoothGattCharacteristic): String =
        BluetoothDeviceBase.full_name_of(characteristic)

    constructor(characteristic: BluetoothGattCharacteristic, value: ByteArray?, cb: ((Int)->Unit)? = null) {
        on_complete = cb
        if (value == null)
            on_execute = { gatt: BluetoothGatt, util: LogUtil ->
                if (!gatt.readCharacteristic(characteristic))
                    util.fail("can't read %s", name_of(characteristic))
            }
        else
            on_execute = { gatt: BluetoothGatt, util: LogUtil ->
                if (!characteristic.setValue(value))
                    util.fail("can't set value for %s", name_of(characteristic))
                if (!gatt.writeCharacteristic(characteristic))
                    util.fail("can't write %s", name_of(characteristic))
            }
    }

    constructor(descriptor: BluetoothGattDescriptor, value: ByteArray?, cb: ((Int)->Unit)? = null) {
        on_complete = cb
        if (value == null)
            on_execute = { gatt: BluetoothGatt, util: LogUtil ->
                if (!gatt.readDescriptor(descriptor))
                    util.fail("can't read %s for %s", name_of(descriptor), name_of(descriptor.characteristic))
            }
        else
            on_execute = { gatt: BluetoothGatt, util: LogUtil ->
                if (!descriptor.setValue(value))
                    util.fail("can't set %s value for %s", name_of(descriptor), name_of(descriptor.characteristic))
                if (!gatt.writeDescriptor(descriptor))
                    util.fail("can't write %s for %s", name_of(descriptor), name_of(descriptor.characteristic))
            }
    }

    public fun execute(gatt: BluetoothGatt, util: LogUtil) {
        on_execute.invoke(gatt, util)
    }

    public fun complete(status: Int) {
        on_complete?.invoke(status)
    }
}

abstract class BluetoothDeviceBase {
    private val reqs = ConcurrentLinkedQueue<BluetoothGattRequest>()
    private var req_in_progress = false

    val util = LogUtil()
    lateinit var gatt: BluetoothGatt
        private set
    val dev: BluetoothDevice
        get() = gatt.device

    companion object {
        fun canon_uuid(uuid: UUID): String =
            uuid.toString().replace("-0000-1000-8000-00805f9b34fb", "-BT")
        fun alias_of(descriptor: BluetoothGattDescriptor): String? =
            if (descriptor.uuid.equals(GATT_CCCD_UUID)) "CCCD" else null
        fun alias_of(@Suppress("UNUSED_PARAMETER") characteristic: BluetoothGattCharacteristic): String? =
            null
        fun name_of(descriptor: BluetoothGattDescriptor): String =
            alias_of(descriptor) ?: canon_uuid(descriptor.uuid)
        fun name_of(characteristic: BluetoothGattCharacteristic): String =
            alias_of(characteristic) ?: canon_uuid(characteristic.uuid)
        fun full_name_of(descriptor: BluetoothGattDescriptor): String =
            alias_of(descriptor) ?: "descriptor " + canon_uuid(descriptor.uuid)
        fun full_name_of(characteristic: BluetoothGattCharacteristic): String =
            alias_of(characteristic) ?: "characteristic " + canon_uuid(characteristic.uuid)
    }

    open fun onGattConnected(state: Boolean) {}
    open fun onConnectionStateChanged(state: Boolean) {}
    open fun onCharacteristicUpdate(characteristic: BluetoothGattCharacteristic, status: Int) {}

    protected fun queueRead(characteristic: BluetoothGattCharacteristic, cb: ((Int)->Unit)? = null) =
        queueRequest(BluetoothGattRequest(characteristic, null, cb))
    protected fun queueRead(characteristic: BluetoothGattCharacteristic?, cb: ((Int)->Unit)? = null) =
        if (characteristic != null) queueRead(characteristic, cb) else cb?.invoke(BluetoothGatt.GATT_FAILURE)
    protected fun queueRead(descriptor: BluetoothGattDescriptor, cb: ((Int)->Unit)? = null) =
        queueRequest(BluetoothGattRequest(descriptor, null, cb))
    protected fun queueRead(descriptor: BluetoothGattDescriptor?, cb: ((Int)->Unit)? = null) =
        if (descriptor != null) queueRead(descriptor, cb) else cb?.invoke(BluetoothGatt.GATT_FAILURE)

    protected fun queueWrite(characteristic: BluetoothGattCharacteristic, value: ByteArray, cb: ((Int)->Unit)? = null) =
        queueRequest(BluetoothGattRequest(characteristic, value, cb))
    protected fun queueWrite(descriptor: BluetoothGattDescriptor, value: ByteArray, cb: ((Int)->Unit)? = null) =
        queueRequest(BluetoothGattRequest(descriptor, value, cb))

    protected fun queueRequest(req: BluetoothGattRequest) {
        reqs.add(req)
        var start = true
        synchronized(this) {
            if (!req_in_progress)
                req_in_progress = true
            else
                start = false
        }
        //util.log("BT: queueRequest [start: %s]", if (start) "yes" else "NO")
        if (start)
            execRequest(req)
    }

    private fun execRequest(req: BluetoothGattRequest) {
        try {
            //util.log("BT: execRequest")
            req.execute(gatt, util)
        } catch (e: RuntimeException) {
            requestDone(BluetoothGatt.GATT_FAILURE)
        }
    }

    private fun requestDone(status: Int) {
        try {
            reqs.remove().apply {
                complete(status)
                //util.log("BT: requestDone [status: %d]", status)
            }
        } catch (e: NoSuchElementException) {
            util.log("BUG: requestDone but queue empty [status: %d]", status)
        }
        var new_req: BluetoothGattRequest?
        synchronized(this) {
            new_req = reqs.peek()
            if (new_req == null)
                req_in_progress = false;
        }
        new_req?.let { execRequest(it) }
    }

    private val gatt_cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val conn = newState == BluetoothProfile.STATE_CONNECTED
            util.log("%sconnected (%d; status %d)", if (conn) "" else "dis", newState, status)

            if (conn && !gatt.discoverServices()) {
                util.log("discover returned false")
                onGattConnected(false)
            }

            onConnectionStateChanged(conn)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            util.log("new MTU %d (status %d)", mtu, status)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onCharacteristicUpdate(characteristic, BluetoothGatt.GATT_SUCCESS)
        }

        private fun debugLogOp(characteristic: BluetoothGattCharacteristic, desc_name: String?, status: Int, op: String, data: ByteArray) {
            var value = String()
            for (b in data)
                value += String.format("%02x ", b)
            val desc = if (desc_name != null) "/" + desc_name else ""
            util.log("%s%s %s %s (%d)", name_of(characteristic), desc, op, value, status)
        }

        private fun debugOpComplete(descriptor: BluetoothGattDescriptor, status: Int, op: String) {
            debugLogOp(descriptor.characteristic, name_of(descriptor), status, op, descriptor.value)
        }

        private fun debugOpComplete(characteristic: BluetoothGattCharacteristic, status: Int, op: String) {
            debugLogOp(characteristic, null, status, op, characteristic.value)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS)
                util.log("read of %s failed: %d", full_name_of(characteristic), status)
            debugOpComplete(characteristic, status, "?=")
            requestDone(status)
            onCharacteristicUpdate(characteristic, status)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS)
                util.log("write of %s failed: %d", full_name_of(characteristic), status)
            debugOpComplete(characteristic, status, ":=")
            requestDone(status)
        }

        override fun onDescriptorRead(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            debugOpComplete(descriptor, status, "?=")
            requestDone(status)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            debugOpComplete(descriptor, status, ":=")
            requestDone(status)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            util.log("servicesDiscovered; status %d", status)
            onGattConnected(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    protected fun lateInit(ctx: Context, dev: BluetoothDevice, log: LogFun) {
        val debug_prefix = dev.address + "> "
        util.onLog { log(debug_prefix + it) }

        util.log("Device found")

        gatt = if (Build.VERSION.SDK_INT >= 23)
            dev.connectGatt(ctx, true, gatt_cb, BluetoothDevice.TRANSPORT_LE)
        else
            dev.connectGatt(ctx, true, gatt_cb)
        if (!gatt.discoverServices())
            onGattConnected(true)
    }

    protected fun setNotify(gatt_char: BluetoothGattCharacteristic, enable: Boolean, cb: ((Int) -> Unit)? = null)
    {
        util.log("%sabling notification for %s", if (enable) "en" else "dis", name_of(gatt_char))
        if (!gatt.setCharacteristicNotification(gatt_char, enable))
            util.log("can't %sable notification receive for %s", if (enable) "en" else "dis", name_of(gatt_char))
        val value = if (enable) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        queueWrite(gatt_char.getDescriptor(GATT_CCCD_UUID), value, cb)
    }
}

private class BluetoothDeviceStub : BluetoothDeviceBase()

abstract class BluetoothDeviceManagerBase {
    val util = LogUtil()

    lateinit var ctx: Context
        private set
    lateinit var loop: Handler
        private set
    lateinit var btmgr: BluetoothManager
        private set
    private var scan_filter: List<ScanFilter> = listOf<ScanFilter>()
    private var restarting = false

    protected abstract fun onNewDevice(dev: BluetoothDevice): BluetoothDeviceBase?

    private val devs = mutableMapOf<String, BluetoothDeviceBase>()
    val devices
        get() = devs.asSequence().filter { it.value !is BluetoothDeviceStub } .map { it.key to it.value }

    private fun noticeDevice(dev: BluetoothDevice) {
        //util.log("A> dev %s id %s", dev.address, System.identityHashCode(dev))
        synchronized(this) {
            val bd = devs.getOrPut(dev.address) { BluetoothDeviceStub() }
            if (bd !is BluetoothDeviceStub)
                return
        }

        val new = onNewDevice(dev)
        synchronized(this) {
            if (new != null)
                devs[dev.address] = new
            else
                devs.remove(dev.address)
        }
    }

    protected fun removeDevice(dev: BluetoothDeviceBase): Boolean {
        //util.log("R> dev %s id %s", dev.dev.address, System.identityHashCode(dev.dev))
        synchronized(this) {
            val bd = devs[dev.dev.address]
            if (bd != dev)
                return false

            devs.remove(dev.dev.address)
            return true
        }
    }

    private val scan_cb = object : ScanCallback() {
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.let {
                for (entry in it) {
                    //util.log("BR: %s", entry.device.address)
                    loop.post { noticeDevice(entry.device) }
                }
            }
        }

        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let {
                //util.log("SR%d: %s", callbackType, it.device.address)
                loop.post { noticeDevice(it.device) }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            util.log("Scan failed: %s", getScanErrorString(errorCode))
            if (errorCode != SCAN_FAILED_ALREADY_STARTED) {
                restarting = true
                btmgr.adapter.disable()
            }
        }
    }

    private val btevent = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = when (intent.action) {
            BluetoothAdapter.ACTION_STATE_CHANGED -> onBluetoothStateChanged(
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
            )
            BluetoothDevice.ACTION_FOUND -> onDeviceFound(
                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)!!,
                intent.getStringExtra(BluetoothDevice.EXTRA_NAME),
                intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, -9999).toInt()
            )
            BluetoothDevice.ACTION_NAME_CHANGED -> onDeviceNameChanged(
                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)!!,
                intent.getStringExtra(BluetoothDevice.EXTRA_NAME)!!
            )
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> onDeviceBondStateChanged(
                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)!!,
                intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1),
                intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
            )
            BluetoothDevice.ACTION_UUID -> onDeviceUUIDs(
                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)!!,
                intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)!!.map { it as ParcelUuid }
            )
            else -> {}
        }

        fun register(context: Context) {
            val actions = listOf(
                BluetoothAdapter.ACTION_STATE_CHANGED,
                BluetoothDevice.ACTION_FOUND,
                BluetoothDevice.ACTION_NAME_CHANGED,
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothDevice.ACTION_UUID
            )
            for (a in actions)
                context.registerReceiver(this, IntentFilter(a))
        }

        fun unregister(context: Context) {
            context.unregisterReceiver(this)
        }
    }

    private fun onBluetoothStateChanged(state: Int) {
        util.log("BT state changed: %d", state)
        if (restarting) when (state) {
            BluetoothAdapter.STATE_OFF -> btmgr.adapter.enable()
            BluetoothAdapter.STATE_ON -> {
                restarting = false
                restartScanning()
            }
        }
    }

    protected open fun onDeviceFound(dev: BluetoothDevice, name: String?, rssi: Int) {
        val rssi_str = if (rssi != -9999) String.format(" [RSSI: %d]", rssi) else ""
        util.log("%s) found: %s%s", dev.address, name ?: "(unknown)", rssi_str)
    }

    protected open fun onDeviceNameChanged(dev: BluetoothDevice, name: String) {
        //util.log("%s) name changed: %s", dev.address, name)
    }

    protected open fun onDeviceBondStateChanged(dev: BluetoothDevice, state: Int, prevState: Int) {
        util.log("%s) bonding state: %d <- %d", dev.address, state, prevState)
    }

    protected open fun onDeviceUUIDs(dev: BluetoothDevice, uuids: List<ParcelUuid>) {
        for (uuid in uuids)
            util.log("%s) UUID: %s", dev.address, uuid.toString())
    }

    private fun restartScanning() {
        val scanset = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED)
        if (Build.VERSION.SDK_INT >= 23)
            scanset.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
        btmgr.adapter.bluetoothLeScanner.startScan(scan_filter, scanset.build(), scan_cb)
    }

    fun startScanning(service_uuids: List<UUID> = listOf<UUID>()) {
        scan_filter = service_uuids.map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build() }
        restartScanning()
    }

    fun stopScanning() {
        btmgr.adapter.bluetoothLeScanner.stopScan(scan_cb)
    }

    open fun initialize(ctx: Activity, requestCodeBT: Int = 0): Unit {
        this.ctx = ctx
        this.loop = Handler(ctx.mainLooper)
        btmgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        if (!btmgr.adapter.isEnabled()) {
            ctx.startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), requestCodeBT)
            throw IllegalStateException("Bluetooth disabled")
        }

        btevent.register(ctx)

        for (dev in btmgr.getConnectedDevices(BluetoothProfile.GATT))
            loop.post { noticeDevice(dev) }
    }

    open fun cleanup() {
        btevent.unregister(ctx)
    }
}
