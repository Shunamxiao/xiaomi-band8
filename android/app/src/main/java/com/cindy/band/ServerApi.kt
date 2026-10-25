package com.cindy.band

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class RelayConfig(
    val enabled: Boolean = true,
    val blockedPackages: Set<String> = emptySet(),
    val appName: String = "Cindy",
)

object ServerApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun config(baseUrl: String): RelayConfig? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(baseUrl + "/api/v1/config").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val json = JSONObject(response.body?.string() ?: return@withContext null)
                val blocked = mutableSetOf<String>()
                json.optJSONArray("blocked_packages")?.let { array ->
                    for (i in 0 until array.length()) blocked.add(array.getString(i))
                }
                RelayConfig(
                    enabled = json.optBoolean("enabled", true),
                    blockedPackages = blocked,
                    appName = json.optString("app_name", "Cindy"),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun postEvent(baseUrl: String, payload: JSONObject) = withContext(Dispatchers.IO) {
        try {
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(baseUrl + "/api/v1/events").post(body).build()
            client.newCall(request).execute().close()
        } catch (_: Exception) {
        }
    }

    suspend fun deskStop(baseUrl: String) = withContext(Dispatchers.IO) {
        try {
            val body = "{}".toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(baseUrl + "/api/v1/desk/stop").post(body).build()
            client.newCall(request).execute().close()
        } catch (_: Exception) {
        }
    }

    /** 把本地开关同步到服务端配置，保证网页控制台与 App 一致 */
    suspend fun saveConfig(
        baseUrl: String,
        enabled: Boolean,
        appName: String,
        notifyVibrate: Boolean,
    ) = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("enabled", enabled)
                put("app_name", appName)
                put("notify_vibrate", notifyVibrate)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(baseUrl + "/api/v1/config").post(body).build()
            client.newCall(request).execute().close()
        } catch (_: Exception) {
        }
    }

    suspend fun commands(baseUrl: String): JSONArray = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(baseUrl + "/api/v1/commands").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext JSONArray()
                JSONObject(response.body?.string() ?: "{}").optJSONArray("items") ?: JSONArray()
            }
        } catch (_: Exception) {
            JSONArray()
        }
    }
}

