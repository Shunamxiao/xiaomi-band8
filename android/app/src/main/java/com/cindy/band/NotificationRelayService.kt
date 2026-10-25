package com.cindy.band

import android.app.Notification
import android.util.Log
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationRelayService : NotificationListenerService() {

    private val recent = LinkedHashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Log.i("CindyBand", "notify posted: " + sbn.packageName + " tag=" + sbn.tag)
        if (!Prefs.relayEnabled(this)) return
        val packageName = sbn.packageName ?: return
        val isRelayTest = sbn.tag == "relay_test"
        if (packageName == this.packageName && !isRelayTest) { Log.i("CindyBand", "skip self"); return }
        if (packageName == "android" || packageName.startsWith("com.android.systemui")) return
        if (!isRelayTest && packageName in Prefs.blockedPackages(this)) return
        if (!isRelayTest && packageName in BandService.relayConfig.blockedPackages) return
        if (!BandService.relayConfig.enabled) { Log.i("CindyBand", "skip disabled"); return }

        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val key = sbn.key ?: (packageName + ":" + title + ":" + text)
        val now = System.currentTimeMillis()
        val previous = recent[key]
        if (previous != null && now - previous < 2000) return
        recent[key] = now
        if (recent.size > 64) {
            val oldest = recent.keys.firstOrNull()
            if (oldest != null) recent.remove(oldest)
        }

        val appName = packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(packageName, 0)
        ).toString()

        Log.i("CindyBand", "forward " + packageName + " title=" + title)
        BandService.instance?.forwardNotification(packageName, appName, title, text)
    }
}

