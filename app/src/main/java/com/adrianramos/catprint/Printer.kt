package com.adrianramos.catprint

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

open class PrintException(message: String) : Exception(message)

/** Could not open a connection: the saved address may be stale. */
class ConnectException(message: String) : PrintException(message)

/** What gets printed: 1 bpp rows (true = black), plus 4 bpp burn levels for printers that can. */
class PrintJob(val rows: List<BooleanArray>, val grayLevels: List<ByteArray>?, val energy: Int)

/** rssi: signal strength when found by a scan (dBm, higher = closer); 0 if unknown. */
data class PrinterInfo(val address: String, val name: String, val rssi: Int = 0) {
    val isMxw01 get() = name in Protocol.MXW01_NAMES
}

/**
 * Blocking BLE client for the cat printers; call it from a background thread.
 * Ported from catprinter/ble.py and catprinter/mxw01.py.
 */
@SuppressLint("MissingPermission") // The activity requests the permissions before printing.
class Printer(private val context: Context, private val log: (String) -> Unit) {

    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private var mtu = 23
    private val connected = LinkedBlockingQueue<Boolean>()
    private val events = LinkedBlockingQueue<String>()
    private val notifications = LinkedBlockingQueue<ByteArray>()

    // ---- discovery ----------------------------------------------------------------------------

    /**
     * Finds every printer in range, closest (strongest signal) first. Once the first one shows up
     * it keeps listening for SCAN_SETTLE_MS, so that others in the room get a chance to answer.
     */
    fun scanAll(timeoutS: Long = SCAN_TIMEOUT_S): List<PrinterInfo> {
        if (adapter == null || !adapter.isEnabled) throw PrintException("Activa el Bluetooth.")
        val scanner = adapter.bluetoothLeScanner ?: throw PrintException("Activa el Bluetooth.")
        val found = ConcurrentHashMap<String, PrinterInfo>()
        val first = CountDownLatch(1)
        val serviceUuids = Protocol.SERVICE_UUIDS.map { ParcelUuid(it) }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
                val uuids = result.scanRecord?.serviceUuids.orEmpty()
                if (uuids.any { it in serviceUuids } || name in Protocol.MXW01_NAMES || name in Protocol.LEGACY_NAMES) {
                    val address = result.device.address
                    // Not every advertisement carries the name: keep the one we already have.
                    val known = found[address]?.name.orEmpty()
                    found[address] = PrinterInfo(address, name.ifEmpty { known }, result.rssi)
                    first.countDown()
                }
            }

            override fun onScanFailed(errorCode: Int) {
                log("Error al buscar ($errorCode)")
            }
        }
        log("Buscando impresoras…")
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        try {
            if (!first.await(timeoutS, TimeUnit.SECONDS)) throw PrintException(
                if (Build.VERSION.SDK_INT < 31) "No encontré la impresora. ¿Está encendida y cerca? (En este Android la ubicación debe estar activada.)"
                else "No encontré la impresora. ¿Está encendida y cerca?")
            Thread.sleep(SCAN_SETTLE_MS)
        } finally {
            scanner.stopScan(callback)
        }
        return found.values.sortedByDescending { it.rssi }
    }

    // ---- connection ---------------------------------------------------------------------------

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            connected.offer(status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED)
            if (newState == BluetoothProfile.STATE_DISCONNECTED) events.offer("disconnected")
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { events.offer("services:$status") }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@Printer.mtu = mtu
            events.offer("mtu")
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { events.offer("descriptor:$status") }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { events.offer("write:$status") }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            notifications.offer(value.copyOf())
        }

        @Deprecated("Android < 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            notifications.offer(c.value.copyOf())
        }
    }

    private fun await(prefix: String, timeoutS: Long = 10): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutS)
        while (true) {
            val left = deadline - System.nanoTime()
            val e = events.poll(maxOf(left, 0), TimeUnit.NANOSECONDS)
                ?: throw PrintException("La impresora no respondió.")
            if (e == "disconnected") throw PrintException("Se perdió la conexión con la impresora.")
            if (e.startsWith(prefix)) return e.substringAfter(':', "")
        }
    }

    private fun connectOnce(device: BluetoothDevice): Boolean {
        connected.clear(); events.clear(); notifications.clear()
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        gatt = g
        val ok = connected.poll(CONNECT_TIMEOUT_S, TimeUnit.SECONDS) == true
        if (!ok) { g.disconnect(); g.close(); gatt = null }
        return ok
    }

    private fun connect(info: PrinterInfo) {
        val device = adapter.getRemoteDevice(info.address)
        for (attempt in 1..CONNECT_ATTEMPTS) {
            log("Conectando a ${info.name} (intento $attempt)…")
            if (connectOnce(device)) break
            if (attempt == CONNECT_ATTEMPTS) throw ConnectException(
                "No pude conectar. Cierra otras apps que usen la impresora o apágala y vuelve a encenderla.")
            Thread.sleep(CONNECT_RETRY_DELAY_MS * attempt)
        }
        val g = gatt!!
        g.requestMtu(517)
        runCatching { await("mtu", 5) }
        g.discoverServices()
        if (await("services") != BluetoothGatt.GATT_SUCCESS.toString()) throw PrintException("No pude leer los servicios.")
        log("Conectado (MTU $mtu)")
    }

    fun disconnect() {
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
    }

    private fun characteristic(uuid: java.util.UUID): BluetoothGattCharacteristic {
        val g = gatt!!
        for (s in Protocol.SERVICE_UUIDS) g.getService(s)?.getCharacteristic(uuid)?.let { return it }
        return g.services.firstNotNullOfOrNull { it.getCharacteristic(uuid) }
            ?: throw PrintException("Esta impresora no es compatible (falta $uuid).")
    }

    private fun enableNotifications(c: BluetoothGattCharacteristic) {
        val g = gatt!!
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(Protocol.CCCD_UUID) ?: return
        val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v)
        else @Suppress("DEPRECATION") run { d.value = v; g.writeDescriptor(d) }
        await("descriptor")
    }

    /** Writes and waits for Android to accept the next one (it queues only one at a time). */
    private fun write(c: BluetoothGattCharacteristic, data: ByteArray, noResponse: Boolean) {
        val g = gatt!!
        val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        repeat(200) {
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, data, type) == BluetoothGatt.GATT_SUCCESS
            } else @Suppress("DEPRECATION") run {
                c.writeType = type; c.value = data; g.writeCharacteristic(c)
            }
            if (ok) { await("write"); return }
            Thread.sleep(10) // busy
        }
        throw PrintException("Se perdió la conexión con la impresora.")
    }

    // ---- printing -----------------------------------------------------------------------------

    fun print(info: PrinterInfo, job: PrintJob) {
        try {
            connect(info)
            if (info.isMxw01) printMxw01(job) else printLegacy(job)
        } finally {
            disconnect()
        }
    }

    private fun printLegacy(job: PrintJob) {
        val tx = characteristic(Protocol.TX_UUID)
        enableNotifications(characteristic(Protocol.RX_UUID))
        val data = Protocol.Legacy.job(job.rows, job.energy)
        val chunk = mtu - 3
        val noResponse = tx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val total = (data.size + chunk - 1) / chunk
        for ((i, start) in (data.indices step chunk).withIndex()) {
            write(tx, data.copyOfRange(start, minOf(start + chunk, data.size)), noResponse)
            Thread.sleep(LEGACY_CHUNK_DELAY_MS)
            if (i % 50 == 0) log("Enviando… ${100 * i / total}%")
        }
        log("Imprimiendo…")
        val deadline = System.currentTimeMillis() + LEGACY_DONE_TIMEOUT_S * 1000
        while (System.currentTimeMillis() < deadline) {
            val n = notifications.poll(1, TimeUnit.SECONDS) ?: continue
            if (n.contentEquals(Protocol.Legacy.PRINTER_READY)) return
        }
        throw PrintException("La impresora no confirmó el final de la impresión.")
    }

    private fun waitFor(cmd: Int, timeoutS: Long): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutS * 1000
        while (true) {
            val left = deadline - System.currentTimeMillis()
            val n = notifications.poll(maxOf(left, 0), TimeUnit.MILLISECONDS)
                ?: throw PrintException("La impresora no respondió.")
            if (n.size >= 6 && n[0] == 0x22.toByte() && n[1] == 0x21.toByte() && (n[2].toInt() and 0xff) == cmd) return n
        }
    }

    private fun printMxw01(job: PrintJob) {
        val m = Protocol.Mxw01
        val tx = characteristic(Protocol.TX_UUID)
        val data = characteristic(Protocol.DATA_UUID)
        enableNotifications(characteristic(Protocol.RX_UUID))

        write(tx, m.cmd(m.CMD_GET_STATUS, 0), true)
        val status = waitFor(m.CMD_GET_STATUS, 10)
        if (status.size >= 14) {
            if (status[12].toInt() != 0) {
                val code = status[13].toInt() and 0xff
                throw PrintException("La impresora reporta: " + (m.STATUS_ERRORS[code] ?: "error 0x%02x".format(code)))
            }
            log("Batería: ${status[9].toInt() and 0xff}%")
        }

        val intensity = Math.round(job.energy * 100.0 / 0xffff).toInt()
        write(tx, m.cmd(m.CMD_PRINT_INTENSITY, intensity), true)

        val (rows, mode) = if (job.grayLevels != null) m.grayRows(job.grayLevels) to m.MODE_GRAYSCALE
        else m.monoRows(job.rows) to m.MODE_MONOCHROME
        val n = rows.size
        if (n > m.MAX_ROWS) throw PrintException("Demasiado largo: $n filas (máximo ${m.MAX_ROWS}).")
        write(tx, m.cmd(m.CMD_PRINT, n and 0xff, n shr 8, 0x30, mode), true)
        val resp = waitFor(m.CMD_PRINT, 10)
        if (resp.size > 6 && resp[6].toInt() != 0) throw PrintException("La impresora rechazó el trabajo.")

        // Group whole rows into writes as large as the MTU allows.
        val perWrite = maxOf(1, (mtu - 3) / rows[0].size)
        val chunks = rows.chunked(perWrite)
        for ((i, group) in chunks.withIndex()) {
            val buf = ByteArray(group.sumOf { it.size })
            var off = 0
            for (r in group) { r.copyInto(buf, off); off += r.size }
            write(data, buf, true)
            Thread.sleep(MXW01_CHUNK_DELAY_MS)
            if (i % 40 == 0) log("Enviando… ${100 * i / chunks.size}%")
        }
        write(tx, m.cmd(m.CMD_PRINT_DATA_FLUSH, 0), true)
        log("Imprimiendo…")
        waitFor(m.CMD_PRINT_COMPLETE, 15 + n / 15L)
    }

    companion object {
        const val SCAN_TIMEOUT_S = 10L
        const val SCAN_SETTLE_MS = 2500L
        const val CONNECT_TIMEOUT_S = 10L
        const val CONNECT_ATTEMPTS = 3
        const val CONNECT_RETRY_DELAY_MS = 2000L
        const val LEGACY_CHUNK_DELAY_MS = 20L
        const val LEGACY_DONE_TIMEOUT_S = 30L
        const val MXW01_CHUNK_DELAY_MS = 8L
    }
}
