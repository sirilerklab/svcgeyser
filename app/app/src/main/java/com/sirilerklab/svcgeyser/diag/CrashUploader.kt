package com.sirilerklab.svcgeyser.diag

import android.content.Context
import android.os.Build
import android.util.Log
import com.sirilerklab.svcgeyser.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Uploads a captured crash trace to the crash-log Worker endpoint.
 *
 * POST <CRASH_UPLOAD_URL> with `Authorization: Bearer <CRASH_UPLOAD_API_KEY>` and a JSON body
 * describing the device/build plus the exception and full stack trace. Expects a response of
 * `{"success":true,"id":"...","url":"..."}`.
 */
object CrashUploader {

    private const val TAG = "SVCGeyser.Crash"
    private const val JSON = "application/json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class Uploaded(val id: String, val url: String)

    /** True when an endpoint is configured at build time. */
    val isConfigured: Boolean get() = BuildConfig.CRASH_UPLOAD_URL.isNotBlank()

    suspend fun upload(context: Context, stackTrace: String): Result<Uploaded> =
        withContext(Dispatchers.IO) {
            runCatching {
                val endpoint = BuildConfig.CRASH_UPLOAD_URL
                require(endpoint.isNotBlank()) { "Crash upload endpoint is not configured" }

                val payload = JSONObject().apply {
                    put("packageName", context.packageName)
                    put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    put("androidVersion", Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString())
                    put("buildVersion", BuildConfig.VERSION_NAME)
                    put("buildNumber", BuildConfig.VERSION_CODE.toString())
                    put("buildType", if (BuildConfig.DEBUG) "debug" else "release")
                    put("exception", stackTrace.lineSequence().firstOrNull()?.trim().orEmpty()
                        .ifBlank { "Unknown exception" })
                    put("stackTrace", stackTrace)
                }

                val request = Request.Builder()
                    .url(endpoint)
                    .post(payload.toString().toRequestBody(JSON.toMediaType()))
                    .apply {
                        if (BuildConfig.CRASH_UPLOAD_API_KEY.isNotBlank()) {
                            header("Authorization", "Bearer ${BuildConfig.CRASH_UPLOAD_API_KEY}")
                        }
                    }
                    .build()

                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    check(resp.isSuccessful) { "HTTP ${resp.code}: $body" }
                    val json = JSONObject(body)
                    check(json.optBoolean("success", false)) { "Server rejected report: $body" }
                    Uploaded(id = json.optString("id"), url = json.optString("url"))
                }
            }.onFailure { Log.w(TAG, "Crash upload failed", it) }
        }
}
