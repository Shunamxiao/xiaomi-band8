package com.cindy.band

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val SERVICE_UUID: UUID = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")
private val CH_WRITE: UUID = UUID.fromString("00000052-0000-1000-8000-00805f9b34fb")
private val CH_READ: UUID = UUID.fromString("00000051-0000-1000-8000-00805f9b34fb")
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private const val FRAME_COMMAND = 2
private const val FLAG_PLAIN = 2
private const val FLAG_ENCRYPTED = 1

private const val SERVICE_AUTH = 1
private const val SERVICE_SYSTEM = 2
private const val SERVICE_NOTIFICATION = 7

private const val CMD_APP_VERIFY = 26
private const val CMD_APP_CONFIRM = 27
private const val SYSTEM_BATTERY = 1
private const val SYSTEM_DEVICE_INFO = 2
private const val SYSTEM_LANGUAGE = 6
private const val SYSTEM_MISC_GET = 14
private const val SYSTEM_SILENT_FROM_PHONE = 44
private const val SYSTEM_FIND_WATCH = 18
private const val NOTIFY_SEND = 0
private const val NOTIFY_SCREEN_ON_GET = 6

object Pb {
    fun varint(value: Int): ByteArray {
        var v = value
        val out = ByteArrayOutputStream()
        while (true) {
            val part = v and 0x7F
            v = v ushr 7
            if (v != 0) {
                out.write(part or 0x80)
            } else {
                out.write(part)
                return out.toByteArray()
            }
        }
    }

    fun int(field: Int, value: Int): ByteArray = varint(field shl 3) + varint(value)

    fun bytes(field: Int, data: ByteArray): ByteArray =
        varint((field shl 3) or 2) + varint(data.size) + data

    fun bytes(field: Int, data: String): ByteArray = bytes(field, data.toByteArray())

    fun parse(buf: ByteArray): Map<Int, MutableList<Pair<String, Any>>> {
        val out = LinkedHashMap<Int, MutableList<Pair<String, Any>>>()
        var pos = 0
        while (pos < buf.size) {
            val (tag, p1) = readVarint(buf, pos)
            pos = p1
            val field = tag ushr 3
            when (tag and 7) {
                0 -> {
                    val (value, p2) = readVarint(buf, pos)
                    pos = p2
                    out.getOrPut(field) { mutableListOf() }.add("int" to value)
                }
                2 -> {
                    val (len, p2) = readVarint(buf, pos)
                    pos = p2
                    val end = minOf(pos + len, buf.size)
                    out.getOrPut(field) { mutableListOf() }.add("bytes" to buf.copyOfRange(pos, end))
                    pos = end
                }
                else -> return out
            }
        }
        return out
    }

    fun readVarint(buf: ByteArray, start: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var pos = start
        while (pos < buf.size) {
            val b = buf[pos].toInt() and 0xFF
            pos++
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result to pos
            shift += 7
        }
        return result to pos
    }
}

object Ccm {
    fun crypt(encrypt: Boolean, key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        val engine = CCMBlockCipher.newInstance(AESEngine.newInstance())
        engine.init(encrypt, AEADParameters(KeyParameter(key), 32, nonce, null))
        val out = ByteArray(engine.getOutputSize(data.size))
        var len = engine.processBytes(data, 0, data.size, out, 0)
        len += engine.doFinal(out, len)
        return out.copyOf(len)
    }
}

class BandSession(
    val deviceKey: ByteArray,
    val appKey: ByteArray,
    val deviceIv: ByteArray,
    val appIv: ByteArray,
) {
    var index = 0
        private set

    fun encrypt(plain: ByteArray): Pair<Int, ByteArray> {
        index += 1
        val nonce = appIv + ByteArray(4) + leInt(index)
        return index to Ccm.crypt(true, appKey, nonce, plain)
    }

    fun decrypt(cipher: ByteArray): ByteArray =
        Ccm.crypt(false, deviceKey, deviceIv + ByteArray(8), cipher)

    private fun leInt(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )
}

class BandClient(
    private val context: Context,
    private val address: String,
    private val deviceKey: ByteArray,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private val writeMutex = Mutex()
    private var pendingWrite: CompletableDeferred<Boolean>? = null
    private var connectionDone: CompletableDeferred<Boolean>? = null
    private var servicesReady = CompletableDeferred<Boolean>()

    private val rawInbound = Channel<ByteArray>(Channel.UNLIMITED)
    private val messages = Channel<ByteArray>(Channel.UNLIMITED)

    private var chunkExpected = 0
    private val chunkBuffer = LinkedHashMap<Int, ByteArray>()
    private var chunkSendStart = CompletableDeferred<Boolean>()
    private var chunkSendEnd = CompletableDeferred<Boolean>()
    private var pendingChunkPayload: ByteArray? = null

    var maxWrite = 244
        private set

    var session: BandSession? = null
        private set

    var connected = false
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectionDone?.complete(true)
                g.discoverServices()
            } else {
                connected = false
                runCatching { g.close() }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                g.requestMtu(247)
                scope.launch {
                    delay(800)
                    servicesReady.complete(true)
                }
            } else {
                servicesReady.complete(false)
                connectionDone?.complete(false)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            maxWrite = mtu - 3
            servicesReady.complete(true)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == CH_READ) {
                characteristic.value?.let { rawInbound.trySend(it) }
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
            pendingWrite = null
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
            pendingWrite = null
        }
    }

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        val device = adapter?.getRemoteDevice(address) ?: return@withContext false
        connectionDone = CompletableDeferred()
        servicesReady = CompletableDeferred()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        val connectedOk = withTimeoutOrNull(15000) { connectionDone?.await() } ?: false
        if (!connectedOk) return@withContext false
        val servicesOk = withTimeoutOrNull(5000) { servicesReady.await() } ?: false
        if (!servicesOk) return@withContext false
        if (!enableNotifications()) return@withContext false
        connected = true
        scope.launch { processIncoming() }
        true
    }

    @Suppress("DEPRECATION")
    private suspend fun enableNotifications(): Boolean {
        val g = gatt ?: return false
        val ch = g.getService(SERVICE_UUID)?.getCharacteristic(CH_READ) ?: return false
        if (!g.setCharacteristicNotification(ch, true)) return false
        val descriptor = ch.getDescriptor(CCCD_UUID) ?: return false
        return withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val done = CompletableDeferred<Boolean>()
                pendingWrite = done
                val started: Boolean
                if (Build.VERSION.SDK_INT >= 33) {
                    started = g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                } else {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    started = g.writeDescriptor(descriptor)
                }
                if (!started) {
                    pendingWrite = null
                    return@withLock false
                }
                withTimeoutOrNull(3000) { done.await() } ?: false
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun writeTo(uuid: UUID, value: ByteArray): Boolean = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val g = gatt ?: return@withLock false
            val ch = g.getService(SERVICE_UUID)?.getCharacteristic(uuid) ?: return@withLock false
            val done = CompletableDeferred<Boolean>()
            pendingWrite = done
            val started: Boolean
            if (Build.VERSION.SDK_INT >= 33) {
                started = g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                ch.value = value
                started = g.writeCharacteristic(ch)
            }
            if (!started) {
                pendingWrite = null
                return@withLock false
            }
            withTimeoutOrNull(3000) { done.await() } ?: false
        }
    }

    private fun u16(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    private fun readU16(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)

    private suspend fun processIncoming() {
        for (frame in rawInbound) {
            try {
                handleFrame(frame)
            } catch (e: Exception) {
                Log.e("CindyBand", "incoming error " + e, e)
            }
        }
    }

    private suspend fun handleFrame(frame: ByteArray) {
        run {
            if (frame.size < 3) return@run
            val chunkIndex = readU16(frame, 0)
            if (chunkIndex != 0) {
                if (chunkExpected > 0) {
                    chunkBuffer[chunkIndex] = frame.copyOfRange(2, frame.size)
                    if (chunkBuffer.size == chunkExpected) {
                        val joined = ByteArrayOutputStream()
                        for (i in 1..chunkExpected) {
                            joined.write(chunkBuffer[i] ?: ByteArray(0))
                        }
                        chunkExpected = 0
                        chunkBuffer.clear()
                        writeTo(CH_READ, byteArrayOf(0, 0, 1, 0))
                        val plain = session?.decrypt(joined.toByteArray())
                        if (plain != null) messages.trySend(plain)
                    }
                }
                return@run
            }

            val type = frame[2].toInt() and 0xFF
            when (type) {
                0 -> {
                    val encrypted = frame[3].toInt() and 0xFF
                    chunkExpected = readU16(frame, 4)
                    chunkBuffer.clear()
                    Log.i("CindyBand", "chunked start encrypted=" + encrypted + " chunks=" + chunkExpected)
                    writeTo(CH_READ, byteArrayOf(0, 0, 1, 1))
                }
                1 -> {
                    val subtype = frame[3].toInt() and 0xFF
                    Log.i("CindyBand", "chunked ack subtype=" + subtype)
                    when (subtype) {
                        1 -> {
                            chunkSendStart.complete(true)
                            val payload = pendingChunkPayload
                            if (payload != null) sendChunks(payload)
                        }
                        0 -> chunkSendEnd.complete(true)
                        2 -> {
                            chunkSendStart.complete(false)
                            chunkSendEnd.complete(false)
                        }
                        5 -> {
                            val payload = pendingChunkPayload
                            if (payload != null) {
                                var pos = 4
                                while (pos + 1 < frame.size) {
                                    val idx = readU16(frame, pos) - 1
                                    pos += 2
                                    val chunkSize = maxWrite - 2
                                    val from = idx * chunkSize
                                    if (from < payload.size) {
                                        val to = minOf(from + chunkSize, payload.size)
                                        writeTo(CH_WRITE, u16(idx + 1) + payload.copyOfRange(from, to))
                                    }
                                }
                            }
                        }
                    }
                }
                2 -> {
                    val encrypted = frame[3].toInt() and 0xFF
                    writeTo(CH_READ, byteArrayOf(0, 0, 3, 0))
                    var payload = frame.copyOfRange(4, frame.size)
                    if (encrypted == 1) {
                        payload = session?.decrypt(payload) ?: return@run
                    }
                    messages.trySend(payload)
                }
                3 -> {
                }
            }
        }
    }

    private suspend fun receive(timeoutMs: Long = 5000): ByteArray? =
        withTimeoutOrNull(timeoutMs) { messages.receive() }

    private fun frame(flag: Int, body: ByteArray, index: Int? = null): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, FRAME_COMMAND.toByte(), flag.toByte()))
        if (flag == FLAG_ENCRYPTED && index != null) {
            out.write(u16(index))
        }
        out.write(body)
        return out.toByteArray()
    }

    private suspend fun sendPlain(payload: ByteArray): Boolean =
        writeTo(CH_WRITE, frame(FLAG_PLAIN, payload))

    suspend fun authenticate(): Boolean {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val verify = Pb.int(1, SERVICE_AUTH) + Pb.int(2, CMD_APP_VERIFY) +
                Pb.bytes(3, Pb.bytes(30, Pb.bytes(1, salt)))
        if (!sendPlain(verify)) return false
        val reply = receive() ?: return false

        val command = Pb.parse(reply)
        val authPayload = command[3]?.firstOrNull()?.second as? ByteArray ?: return false
        val watchNonce = Pb.parse(Pb.parse(authPayload)[31]!!.first().second as ByteArray)
        val deviceRandom = watchNonce[1]!!.first().second as ByteArray
        val deviceSign = watchNonce[2]!!.first().second as ByteArray

        val derived = hkdf(deviceKey, salt + deviceRandom, "miwear-auth".toByteArray(), 64)
        val session = BandSession(
            derived.copyOfRange(0, 16),
            derived.copyOfRange(16, 32),
            derived.copyOfRange(32, 36),
            derived.copyOfRange(36, 40),
        )
        val expected = hmac(session.deviceKey, deviceRandom + salt)
        if (!MessageDigest.isEqual(expected, deviceSign)) return false

        val appSign = hmac(session.appKey, salt + deviceRandom)
        val info = Pb.int(1, 0) + byteArrayOf(0x15) + leFloat(Build.VERSION.SDK_INT.toFloat()) +
                Pb.bytes(3, Build.MODEL ?: "Android") + Pb.bytes(5, "CN")
        val encryptedInfo = Ccm.crypt(true, session.appKey, session.appIv + ByteArray(8), info)
        val confirm = Pb.int(1, SERVICE_AUTH) + Pb.int(2, CMD_APP_CONFIRM) +
                Pb.bytes(3, Pb.bytes(32, Pb.bytes(1, appSign) + Pb.bytes(2, encryptedInfo)))
        if (!sendPlain(confirm)) return false

        val reply2 = receive() ?: return false
        val confirmCommand = Pb.parse(reply2)
        val confirmPayload = confirmCommand[3]?.firstOrNull()?.second as? ByteArray ?: return false
        val step4 = Pb.parse(Pb.parse(confirmPayload)[33]!!.first().second as ByteArray)
        val code = step4[1]?.firstOrNull()?.second as? Int ?: return false
        if (code != 1) return false
        this.session = session
        return true
    }

    private fun summary(command: ByteArray): String {
        val f = Pb.parse(command)
        val type = f[1]?.firstOrNull()?.second
        val subtype = f[2]?.firstOrNull()?.second
        return "type=" + type + " subtype=" + subtype + " len=" + command.size
    }

    suspend fun send(command: ByteArray): Boolean {
        val s = session ?: return false
        Log.i("CindyBand", "TX " + summary(command))
        val (index, cipher) = s.encrypt(command)
        val single = frame(FLAG_ENCRYPTED, cipher, index)
        if (single.size <= maxWrite) {
            return writeTo(CH_WRITE, single)
        }
        // 分块发送：payload = [nonceIndex u16][cipher]
        val payload = u16(index) + cipher
        val chunkSize = maxWrite - 2
        val total = (payload.size + chunkSize - 1) / chunkSize
        pendingChunkPayload = payload
        chunkSendStart = CompletableDeferred()
        chunkSendEnd = CompletableDeferred()
        Log.i("CindyBand", "TX chunked total=" + total + " size=" + payload.size)
        writeTo(CH_WRITE, byteArrayOf(0, 0, 0, 1) + u16(total))
        val started = withTimeoutOrNull(3000) { chunkSendStart.await() } ?: false
        if (!started) return false
        return withTimeoutOrNull(6000) { chunkSendEnd.await() } ?: false
    }

    private suspend fun sendChunks(payload: ByteArray) {
        val chunkSize = maxWrite - 2
        var offset = 0
        var index = 1
        while (offset < payload.size) {
            val end = minOf(offset + chunkSize, payload.size)
            writeTo(CH_WRITE, u16(index) + payload.copyOfRange(offset, end))
            offset = end
            index++
        }
    }

    /**
     * 读取命令响应。
     * 手环会主动推送通知等非应答包，必须按 type/subtype 过滤，
     * 否则会把通知误当成命令结果。
     */
    suspend fun readPending(timeoutMs: Long = 1200, type: Int? = null, subtype: Int? = null): ByteArray? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remain = deadline - System.currentTimeMillis()
            if (remain <= 0) return null
            val message = withTimeoutOrNull(remain) { messages.receive() } ?: return null
            if (type == null) return message
            val fields = runCatching { Pb.parse(message) }.getOrNull() ?: continue
            val actualType = fields[1]?.firstOrNull()?.second as? Int
            val actualSubtype = fields[2]?.firstOrNull()?.second as? Int
            if (actualType == type && actualSubtype == subtype) return message
            Log.i("CindyBand", "skip response type=" + actualType + " subtype=" + actualSubtype)
        }
    }

    suspend fun request(command: ByteArray, timeoutMs: Long = 5000): ByteArray? {
        if (!send(command)) return null
        return receive(timeoutMs)
    }

    suspend fun battery(): Int? {
        val plain = request(Pb.int(1, SERVICE_SYSTEM) + Pb.int(2, SYSTEM_BATTERY)) ?: return null
        val system = Pb.parse(Pb.parse(plain)[4]!!.first().second as ByteArray)
        val detail = Pb.parse(Pb.parse(system[2]!!.first().second as ByteArray)[1]!!.first().second as ByteArray)
        return detail[1]?.firstOrNull()?.second as? Int
    }

    /** 对齐官方 App 的初始化序列 */
    suspend fun initialize() {
        listOf(
            Pb.int(1, SERVICE_SYSTEM) + Pb.int(2, SYSTEM_DEVICE_INFO),
            Pb.int(1, SERVICE_SYSTEM) + Pb.int(2, SYSTEM_MISC_GET),
            Pb.int(1, SERVICE_SYSTEM) + Pb.int(2, SYSTEM_LANGUAGE),
            Pb.int(1, SERVICE_SYSTEM) + Pb.int(2, SYSTEM_SILENT_FROM_PHONE) + Pb.bytes(4, Pb.int(1, 0)),
            Pb.int(1, SERVICE_NOTIFICATION) + Pb.int(2, NOTIFY_SCREEN_ON_GET),
            Pb.int(1, SERVICE_NOTIFICATION) + Pb.int(2, 7) + Pb.int(7, 1),
        ).forEach { cmd ->
            send(cmd)
            withTimeoutOrNull(800) { messages.receive() }
        }
    }

    suspend fun keepAlive(): Boolean =
        send(Pb.int(1, SERVICE_SYSTEM) + Pb.int(2, SYSTEM_MISC_GET))

    fun command(type: Int, subtype: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        Pb.int(1, type) + Pb.int(2, subtype) + payload

    suspend fun sendRaw(type: Int, subtype: Int, payload: ByteArray): Boolean =
        send(command(type, subtype, payload))

    suspend fun deviceState(): ByteArray? = request(command(2, 78))

    suspend fun setDnd(enabled: Boolean): Boolean =
        sendRaw(2, 23, Pb.bytes(11, Pb.int(1, if (enabled) 0 else 2)))

    suspend fun setSilentMode(enabled: Boolean): Boolean =
        sendRaw(2, 44, Pb.bytes(37, Pb.bytes(1, Pb.int(1, if (enabled) 1 else 0))))

    suspend fun displayItems(): ByteArray? = request(command(2, 29))

    suspend fun widgetScreens(): ByteArray? = request(command(2, 51))

    suspend fun watchfaceList(): ByteArray? = request(command(4, 0))

    suspend fun musicInfo(state: Int, track: String, artist: String, volume: Int = 50, position: Int = 0, duration: Int = 0): Boolean {
        val info = Pb.int(1, state) + Pb.int(2, volume) + Pb.bytes(4, track) + Pb.bytes(5, artist) +
                Pb.int(6, position) + Pb.int(7, duration)
        return sendRaw(18, 1, Pb.bytes(1, info))
    }

    suspend fun findWatch(): Boolean =
        send(Pb.int(1, SERVICE_SYSTEM) + Pb.int(2, SYSTEM_FIND_WATCH))

    suspend fun notify(
        title: String,
        body: String,
        appName: String = "Cindy",
        packageName: String = "com.cindy.band",
        notificationId: Int = 1,
    ): Boolean {
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.ROOT)
            .format(java.util.Date())
        var text = body
        while (true) {
            val key = "0|" + packageName + "|" + notificationId + "|null|" +
                    (System.currentTimeMillis() % 100000)
            val notification3 = Pb.bytes(1, packageName) + Pb.bytes(2, appName) +
                    Pb.bytes(3, title) + Pb.bytes(4, "") + Pb.bytes(5, text) +
                    Pb.bytes(6, timestamp) + Pb.int(7, notificationId) + Pb.bytes(12, key)
            val notification = Pb.bytes(3, Pb.bytes(1, notification3))
            val command = Pb.int(1, SERVICE_NOTIFICATION) + Pb.int(2, NOTIFY_SEND) + Pb.bytes(9, notification)
            if (command.size + 6 <= maxWrite || text.length <= 8) {
                return send(command)
            }
            text = text.substring(0, text.length * 3 / 4)
        }
    }

    fun close() {
        connected = false
        session = null
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArrayOutputStream()
        var previous = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            out.write(previous)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun leFloat(value: Float): ByteArray {
        val bits = java.lang.Float.floatToIntBits(value)
        return byteArrayOf(
            (bits and 0xFF).toByte(),
            ((bits shr 8) and 0xFF).toByte(),
            ((bits shr 16) and 0xFF).toByte(),
            ((bits shr 24) and 0xFF).toByte(),
        )
    }
}

