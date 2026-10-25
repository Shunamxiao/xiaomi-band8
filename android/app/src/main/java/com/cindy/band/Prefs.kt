package com.cindy.band

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "cindy_band"

    private fun sp(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun serverUrl(context: Context): String =
        sp(context).getString("server_url", "") ?: ""

    fun setServerUrl(context: Context, value: String) {
        sp(context).edit().putString("server_url", value.trim().trimEnd('/')).apply()
    }

    fun bandAddress(context: Context): String =
        sp(context).getString("band_address", "") ?: ""

    fun setBandAddress(context: Context, value: String) {
        sp(context).edit().putString("band_address", value.trim().uppercase()).apply()
    }

    /** 绑定阶段获得的 16 字节密钥（32 位 hex） */
    fun bandKey(context: Context): String =
        sp(context).getString("band_key", "") ?: ""

    fun setBandKey(context: Context, value: String) {
        sp(context).edit().putString("band_key", value.trim().lowercase()).apply()
    }

    fun relayEnabled(context: Context): Boolean =
        sp(context).getBoolean("relay_enabled", true)

    fun setRelayEnabled(context: Context, value: Boolean) {
        sp(context).edit().putBoolean("relay_enabled", value).apply()
    }

    /** 通知是否让手环震动；关闭时仅记录，不推送。 */
    fun notifyVibrate(context: Context): Boolean =
        sp(context).getBoolean("notify_vibrate", true)

    fun setNotifyVibrate(context: Context, value: Boolean) {
        sp(context).edit().putBoolean("notify_vibrate", value).apply()
    }

    fun serviceEnabled(context: Context): Boolean =
        sp(context).getBoolean("service_enabled", true)

    fun setServiceEnabled(context: Context, value: Boolean) {
        sp(context).edit().putBoolean("service_enabled", value).apply()
    }

    fun blockedPackages(context: Context): Set<String> =
        sp(context).getStringSet("blocked", emptySet()) ?: emptySet()

    fun setBlockedPackages(context: Context, value: Set<String>) {
        sp(context).edit().putStringSet("blocked", value).apply()
    }

    fun appName(context: Context): String =
        sp(context).getString("app_name", "Cindy") ?: "Cindy"

    fun setAppName(context: Context, value: String) {
        sp(context).edit().putString("app_name", value).apply()
    }
}