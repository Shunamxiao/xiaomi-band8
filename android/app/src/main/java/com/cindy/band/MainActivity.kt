package com.cindy.band

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 控制台首页：阿里（Ant Design）风格的分组卡片布局。
 * 状态卡片展示链路状态，开关类设置即时写入本地并同步服务端。
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var batteryText: TextView
    private lateinit var deviceText: TextView
    private lateinit var serverInfoText: TextView
    private lateinit var statusDot: android.view.View
    private lateinit var serverInput: EditText
    private lateinit var macInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var relaySwitch: Switch
    private lateinit var vibrateSwitch: Switch

    private val ui = Handler(Looper.getMainLooper())
    private val statusTicker = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.tvStatus)
        batteryText = findViewById(R.id.tvBattery)
        deviceText = findViewById(R.id.tvDevice)
        serverInfoText = findViewById(R.id.tvServerInfo)
        statusDot = findViewById(R.id.dotStatus)
        serverInput = findViewById(R.id.etServer)
        macInput = findViewById(R.id.etMac)
        keyInput = findViewById(R.id.etKey)
        relaySwitch = findViewById(R.id.swRelay)
        vibrateSwitch = findViewById(R.id.swVibrate)

        serverInput.setText(Prefs.serverUrl(this))
        macInput.setText(Prefs.bandAddress(this))
        keyInput.setText(Prefs.bandKey(this))
        relaySwitch.isChecked = Prefs.relayEnabled(this)
        vibrateSwitch.isChecked = Prefs.notifyVibrate(this)

        findViewById<TextView>(R.id.btnStart).setOnClickListener { saveAndStart() }
        findViewById<TextView>(R.id.btnStop).setOnClickListener { stopService() }
        findViewById<android.view.View>(R.id.rowAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<android.view.View>(R.id.rowTest).setOnClickListener { sendTestNotification() }
        findViewById<android.view.View>(R.id.rowLocalTest).setOnClickListener { postLocalNotification() }
        findViewById<android.view.View>(R.id.rowFind).setOnClickListener { findWatch() }
        findViewById<android.view.View>(R.id.rowDeskStop).setOnClickListener { stopDesk() }

        relaySwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setRelayEnabled(this, checked)
            syncConfig()
        }
        vibrateSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setNotifyVibrate(this, checked)
            syncConfig()
            toast(if (checked) "手环通知将震动" else "已关闭震动：通知仅记录，不推送手环")
        }

        requestPermissionsIfNeeded()
        // 用户主动停止后，重新打开 App 不自动拉起服务
        if (Prefs.serviceEnabled(this)) startServiceIfConfigured()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        ui.post(statusTicker)
    }

    override fun onPause() {
        ui.removeCallbacks(statusTicker)
        super.onPause()
    }

    private fun saveAndStart() {
        val url = serverInput.text.toString().trim().trimEnd('/')
        val mac = macInput.text.toString().trim().uppercase()
        val key = keyInput.text.toString().trim().lowercase()

        if (!Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(mac)) {
            toast("MAC 格式应为 AA:BB:CC:DD:EE:FF")
            return
        }
        if (!Regex("^[0-9a-f]{32}$").matches(key)) {
            toast("设备密钥应为 32 位十六进制")
            return
        }
        if (url.isBlank()) {
            toast("请填写服务端地址")
            return
        }

        Prefs.setServerUrl(this, url)
        Prefs.setBandAddress(this, mac)
        Prefs.setBandKey(this, key)
        Prefs.setServiceEnabled(this, true)
        Prefs.setRelayEnabled(this, relaySwitch.isChecked)
        Prefs.setNotifyVibrate(this, vibrateSwitch.isChecked)
        requestPermissionsIfNeeded()

        // 配置变化后重启服务，确保用新设备信息重连
        if (BandService.instance != null) {
            stopService(Intent(this, BandService::class.java))
        }
        startServiceIfConfigured()
        syncConfig()
        toast("已保存，正在连接手环")
        refreshStatus()
    }

    private fun startServiceIfConfigured() {
        startForegroundService(Intent(this, BandService::class.java).apply {
            action = BandService.ACTION_START
        })
    }

    private fun stopService() {
        Prefs.setServiceEnabled(this, false)
        stopService(Intent(this, BandService::class.java))
        statusText.text = "服务已停止"
        refreshStatus()
    }

    private fun sendTestNotification() {
        val service = BandService.instance
        if (service == null) {
            toast("请先启动服务")
            return
        }
        startService(Intent(this, BandService::class.java).apply {
            action = BandService.ACTION_TEST
        })
        toast("已发送测试通知")
    }

    private fun postLocalNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel("relay_test", "转发自测", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, "relay_test")
        } else {
            android.app.Notification.Builder(this)
        }
        manager.notify(
            "relay_test",
            99,
            builder.setContentTitle("本地通知测试")
                .setContentText("这条通知应转发到手环")
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .build()
        )
        toast("已发布本地通知")
    }

    private fun findWatch() {
        val service = BandService.instance
        if (service == null) {
            toast("请先启动服务")
            return
        }
        service.findWatch()
        toast("已发送查找指令")
    }

    private fun stopDesk() {
        CoroutineScope(Dispatchers.IO).launch {
            ServerApi.deskStop(Prefs.serverUrl(this@MainActivity))
        }
        toast("已请求停止桌搭")
    }

    private fun syncConfig() {
        val url = Prefs.serverUrl(this)
        CoroutineScope(Dispatchers.IO).launch {
            ServerApi.saveConfig(
                baseUrl = url,
                enabled = Prefs.relayEnabled(this@MainActivity),
                appName = Prefs.appName(this@MainActivity),
                notifyVibrate = Prefs.notifyVibrate(this@MainActivity),
            )
            BandService.instance?.refreshServer()
        }
    }

    private fun refreshStatus() {
        val service = BandService.instance
        val running = service != null
        val connected = BandService.isLinkReady
        statusText.text = when {
            !running -> "服务未启动"
            connected -> "已连接手环"
            else -> "正在连接手环…"
        }
        statusDot.setBackgroundResource(if (connected) R.drawable.bg_dot else R.drawable.bg_dot_off)
        batteryText.text = "电量 " + (BandService.lastBattery?.let { "$it%" } ?: "--")
        deviceText.text = "Mi Smart Band 8 NFC · " + (BandService.lastDeviceState ?: "等待同步")
        serverInfoText.text = "服务端 " + Prefs.serverUrl(this)
        relaySwitch.isChecked = Prefs.relayEnabled(this)
        vibrateSwitch.isChecked = Prefs.notifyVibrate(this)
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 100)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
