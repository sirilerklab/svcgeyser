package com.sirilerklab.svcgeyser.auth

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "SVCGeyser.Auth"

data class XboxSession(
    val xuid: String,
    val uhs: String,
    val xstsToken: String,
) {
    val authHeader: String get() = "XBL3.0 x=$uhs;$xstsToken"
}

object XboxAuthHelper {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()

    suspend fun exchange(msaAccessToken: String): XboxSession = withContext(Dispatchers.IO) {
        Log.d(TAG, "Exchanging MSA token for Xbox user token…")
        val userToken = exchangeUserToken(msaAccessToken)
        Log.d(TAG, "Got Xbox user token, exchanging for XSTS…")
        val session = exchangeXsts(userToken)
        Log.d(TAG, "XSTS OK — XUID=${session.xuid}")
        session
    }

    private fun exchangeUserToken(msaToken: String): String {
        val body = JSONObject().apply {
            put("Properties", JSONObject().apply {
                put("AuthMethod", "RPS")
                put("SiteName", "user.auth.xboxlive.com")
                put("RpsTicket", "d=$msaToken")
            })
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType", "JWT")
        }.toString()

        Log.d(TAG, "POST https://user.auth.xboxlive.com/user/authenticate")
        val response = http.newCall(
            Request.Builder()
                .url("https://user.auth.xboxlive.com/user/authenticate")
                .post(body.toRequestBody(JSON))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .build()
        ).execute()

        val responseBody = response.body?.string() ?: ""
        Log.d(TAG, "Xbox user-token endpoint → HTTP ${response.code}")

        if (!response.isSuccessful) {
            Log.e(TAG, "Xbox user-token exchange failed — body: $responseBody")
            throw Exception("Xbox user-token exchange failed: HTTP ${response.code} — $responseBody")
        }

        return JSONObject(responseBody).getString("Token")
    }

    private fun exchangeXsts(userToken: String): XboxSession {
        val body = JSONObject().apply {
            put("Properties", JSONObject().apply {
                put("SandboxId", "RETAIL")
                put("UserTokens", org.json.JSONArray().put(userToken))
            })
            put("RelyingParty", "http://xboxlive.com")
            put("TokenType", "JWT")
        }.toString()

        Log.d(TAG, "POST https://xsts.auth.xboxlive.com/xsts/authorize")
        val response = http.newCall(
            Request.Builder()
                .url("https://xsts.auth.xboxlive.com/xsts/authorize")
                .post(body.toRequestBody(JSON))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .build()
        ).execute()

        val responseBody = response.body?.string() ?: ""
        Log.d(TAG, "XSTS endpoint → HTTP ${response.code}")

        if (!response.isSuccessful) {
            Log.e(TAG, "XSTS exchange failed — body: $responseBody")
            // HTTP 401 with XErr in body is the most common failure (account issues).
            throw Exception("XSTS exchange failed: HTTP ${response.code} — $responseBody")
        }

        val json      = JSONObject(responseBody)
        val xstsToken = json.getString("Token")
        val xui       = json.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0)
        val xuid      = xui.getString("xid")
        val uhs       = xui.getString("uhs")
        return XboxSession(xuid = xuid, uhs = uhs, xstsToken = xstsToken)
    }
}
