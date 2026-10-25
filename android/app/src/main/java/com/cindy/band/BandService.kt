package com.cindy.band

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

class BandService : Service() {

    companion object {
        const val ACTION_START = "com.cindy.band.START"
        const val ACTION_STOP = "com.cindy.band.STOP"
        const val ACTION_TEST = "com.cindy.band.TEST"
        const val ACTION_LOCAL_TEST = "com.cindy.band.LOCAL_TEST"
        private const val CHANNEL_ID = "band_service"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var instance: BandService? = null
            private set

        @Volatile
        var relayConfig: RelayConfig = RelayConfig()
            private set

        @Volatile
        var lastBattery: Int? = null
            private set

        /** 链路是否已完成认证并可下发命令 */
        @Volatile
        var isLinkReady: Boolean = false
            private set

        /** 设备状态摘要，供首页状态卡展示 */
        @Volatile
        var lastDeviceState: String? = null
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var client: BandClient? = null
    private var serverUrl = ""
    private var bandAddress = ""
    private var deviceKeyHex = ""
    private val notifyId = AtomicInteger(1)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(NOTIFICATION_ID, buildNotification("正在连接手环…"))
        serverUrl = Prefs.serverUrl(this)
        bandAddress = Prefs.bandAddress(this)
        deviceKeyHex = Prefs.bandKey(this)
        loopJob = scope.launch { connectionLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TEST -> scope.launch {
                val stamp = (System.currentTimeMillis() / 1000).toString()
                client?.notify("Cindy", "测试通知 " + stamp)
            }
            ACTION_LOCAL_TEST -> {
                val manager = getSystemService(NotificationManager::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    manager.createNotificationChannel(
                        NotificationChannel("relay_test", "转发自测", NotificationManager.IMPORTANCE_DEFAULT)
                    )
                }
                val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notification.Builder(this, "relay_test")
                } else {
                    Notification.Builder(this)
                }
                manager.notify(
                    "relay_test",
                    99,
                    builder.setContentTitle("本地通知测试")
                        .setContentText("这条通知应转发到手环")
                        .setSmallIcon(android.R.drawable.stat_notify_chat)
                        .build()
                )
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        isLinkReady = false
        lastDeviceState = null
        loopJob?.cancel()
        scope.cancel()
        client?.close()
        client = null
        super.onDestroy()
    }

    fun forwardNotification(packageName: String, appName: String, title: String, body: String) {
        scope.launch {
            // 固件没有“单条静默通知”字段，关闭震动时选择不打扰：
            // 仅记录事件，不推送到手环。
            if (!Prefs.notifyVibrate(this@BandService)) {
                Log.i("CindyBand", "suppressed (vibrate off) " + packageName)
                ServerApi.postEvent(serverUrl, JSONObject().apply {
                    put("type", "notification_suppressed")
                    put("package", packageName)
                    put("title", title)
                    put("body", body)
                    put("ts", System.currentTimeMillis())
                })
                return@launch
            }
            val band = client
            if (band == null || !band.connected) return@launch
            val ok = band.notify(
                title, body,
                appName = appName,
                packageName = packageName,
                notificationId = notifyId.getAndIncrement() % 100000,
            )
            Log.i("CindyBand", "band notify=" + ok)
            if (ok) {
                ServerApi.postEvent(serverUrl, JSONObject().apply {
                    put("type", "notification")
                    put("package", packageName)
                    put("title", title)
                    put("body", body)
                    put("ts", System.currentTimeMillis())
                })
            }
        }
    }

    fun findWatch() {
        scope.launch { client?.findWatch() }
    }

    fun refreshServer() {
        serverUrl = Prefs.serverUrl(this)
        bandAddress = Prefs.bandAddress(this)
        deviceKeyHex = Prefs.bandKey(this)
        scope.launch { loadConfig() }
    }

    private suspend fun loadConfig() {
        ServerApi.config(serverUrl)?.let { relayConfig = it }
    }

    private suspend fun connectionLoop() {
        while (true) {
            if (bandAddress.isBlank() || deviceKeyHex.length != 32) {
                updateNotification("未配置手环：请填写 MAC 与密钥")
                delay(10000)
                continue
            }
            try {
                if (client?.connected != true) {
                    updateNotification("正在连接手环…")
                    val band = BandClient(
                        applicationContext,
                        bandAddress,
                        hexToBytes(deviceKeyHex),
                    )
                    client = band
                    val isConnected = band.connect()
                    val isAuthed = isConnected && band.authenticate()
                    Log.i("CindyBand", "connect=" + isConnected + " auth=" + isAuthed)
                    isLinkReady = isAuthed
                    if (isAuthed) {
                        lastDeviceState = "已认证 · 通知通道就绪"
                        updateNotification("已连接手环")
                        loadConfig()
                        runCatching { band.initialize() }
                        lastBattery = band.battery()
                        pollServer(band)
                    } else {
                        band.close()
                        client = null
                        isLinkReady = false
                        lastDeviceState = null
                        updateNotification("连接失败，10 秒后重试")
                    }
                } else {
                    delay(15000)
                }
            } catch (e: Exception) {
                Log.e("CindyBand", "connection loop error", e)
                client?.close()
                client = null
                isLinkReady = false
                lastDeviceState = null
            }
            delay(10000)
        }
    }

    private suspend fun pollServer(band: BandClient) {
        var tick = 0
        var keepAliveFailures = 0
        while (band.connected) {
            delay(5000)
            tick++
            if (tick % 12 == 0) loadConfig()
            if (tick % 12 == 6) {
                val alive = runCatching { band.keepAlive() }.getOrDefault(false)
                keepAliveFailures = if (alive) 0 else keepAliveFailures + 1
                Log.i("CindyBand", "keepalive=" + alive + " failures=" + keepAliveFailures)
                if (keepAliveFailures >= 3) {
                    Log.w("CindyBand", "link seems dead, reconnecting")
                    band.close()
                    return
                }
            }
            if (tick % 120 == 0) lastBattery = band.battery()
            val items = ServerApi.commands(serverUrl)
            if (items.length() > 0) Log.i("CindyBand", "commands=" + items.length())
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val vibrate = item.optBoolean("vibrate", true)
                if (!vibrate) {
                    Log.i("CindyBand", "suppressed server notification (vibrate off)")
                    ServerApi.postEvent(serverUrl, JSONObject().apply {
                        put("type", "notification_suppressed")
                        put("package", item.optString("app", "Cindy"))
                        put("title", item.optString("title", ""))
                        put("body", item.optString("body", ""))
                        put("ts", System.currentTimeMillis())
                    })
                    continue
                }
                val rawType = item.optInt("type", -1)
                if (rawType >= 0) {
                    val subtype = item.optInt("subtype", 0)
                    val payloadHex = item.optString("payload", "")
                    val payload = if (payloadHex.isBlank()) ByteArray(0) else hexToBytes(payloadHex)
                    val ok = runCatching { band.sendRaw(rawType, subtype, payload) }
                        .onFailure { Log.e("CindyBand", "sendRaw failed", it) }
                        .getOrDefault(false)
                    Log.i("CindyBand", "raw command type=" + rawType + " subtype=" + subtype + " ok=" + ok)
                    band.readPending(1200, rawType, subtype)?.let { resp ->
                        val hex = resp.joinToString("") { b -> "%02x".format(b) }
                        Log.i("CindyBand", "raw response " + hex)
                        ServerApi.postEvent(serverUrl, JSONObject().apply {
                            put("type", "command_result")
                            put("package", "type=" + rawType + " subtype=" + subtype)
                            put("title", "response")
                            put("body", hex)
                            put("ts", System.currentTimeMillis())
                        })
                    }
                    continue
                }
                val ok = band.notify(
                    item.optString("title", "Cindy"),
                    item.optString("body", ""),
                    appName = item.optString("app", relayConfig.appName),
                    notificationId = notifyId.getAndIncrement() % 100000,
                )
                Log.i("CindyBand", "server notify ok=" + ok)
            }
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "手环连接", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Cindy Band")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
}

// 设备 MAC 与密钥来自 App 内配置，不再硬编码

