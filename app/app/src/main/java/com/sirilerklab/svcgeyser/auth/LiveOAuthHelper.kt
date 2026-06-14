package com.sirilerklab.svcgeyser.auth

import com.sirilerklab.svcgeyser.BuildConfig
import android.app.Activity
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

private const val TAG = "SVCGeyser.Auth"

private const val REDIRECT_URI = "svcgeyser://auth"
private const val AUTH_URL     = "https://login.live.com/oauth20_authorize.srf"
private const val TOKEN_URL    = "https://login.live.com/oauth20_token.srf"
private const val SCOPES       = "XboxLive.signin offline_access"
private const val AUTH_TIMEOUT = 5 * 60 * 1_000L

data class MsaTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSec: Long,
) {
    fun expiresAtMs(): Long = System.currentTimeMillis() + expiresInSec * 1_000L
}

object LiveOAuthHelper {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun clientId(): String {
        val id = BuildConfig.LIVE_OAUTH_CLIENT_ID
        if (id.isBlank()) {
            throw IllegalStateException(
                "LIVE_OAUTH_CLIENT_ID is not configured. " +
                    "Set liveOAuthClientId in app/gradle.properties for local builds."
            )
        }
        return id
    }

    suspend fun signIn(activity: Activity): MsaTokens {
        val verifier  = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)

        val authUri = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id",             clientId())
            .appendQueryParameter("response_type",         "code")
            .appendQueryParameter("redirect_uri",          REDIRECT_URI)
            .appendQueryParameter("scope",                 SCOPES)
            .appendQueryParameter("code_challenge",        challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        Log.d(TAG, "Opening auth URL: $authUri")

        val deferred = CompletableDeferred<String?>()
        OAuthCallbackHolder.pending = deferred

        withContext(Dispatchers.Main) {
            CustomTabsIntent.Builder().build().launchUrl(activity, authUri)
        }

        Log.d(TAG, "Waiting for auth redirect (timeout ${AUTH_TIMEOUT / 1000}s)…")
        val code = withTimeout(AUTH_TIMEOUT) { deferred.await() }
            ?: throw Exception("Sign-in was cancelled or denied.")

        Log.d(TAG, "Auth code received (${code.take(8)}…), exchanging for token…")
        return withContext(Dispatchers.IO) { exchangeCode(code, verifier) }
    }

    suspend fun refreshAccessToken(refreshToken: String): MsaTokens =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("grant_type",    "refresh_token")
                .add("client_id",    clientId())
                .add("refresh_token", refreshToken)
                .add("scope",        SCOPES)
                .build()

            Log.d(TAG, "POST $TOKEN_URL (refresh)")
            val response = http.newCall(
                Request.Builder().url(TOKEN_URL).post(body).build()
            ).execute()

            val responseBody = response.body?.string() ?: ""
            Log.d(TAG, "Refresh endpoint → HTTP ${response.code}")

            if (!response.isSuccessful) {
                Log.e(TAG, "Token refresh failed — body: $responseBody")
                throw Exception("Token refresh failed: HTTP ${response.code}")
            }

            parseTokenResponse(responseBody)
        }

    private fun exchangeCode(code: String, verifier: String): MsaTokens {
        val body = FormBody.Builder()
            .add("grant_type",    "authorization_code")
            .add("client_id",    clientId())
            .add("code",         code)
            .add("redirect_uri", REDIRECT_URI)
            .add("code_verifier", verifier)
            .add("scope",        SCOPES)
            .build()

        Log.d(TAG, "POST $TOKEN_URL")
        val response = http.newCall(
            Request.Builder().url(TOKEN_URL).post(body).build()
        ).execute()

        val responseBody = response.body?.string() ?: ""
        Log.d(TAG, "Token endpoint → HTTP ${response.code}")

        if (!response.isSuccessful) {
            Log.e(TAG, "Token exchange failed — body: $responseBody")
            throw Exception("Token exchange failed: HTTP ${response.code} — $responseBody")
        }

        Log.d(TAG, "Token exchange OK")
        return parseTokenResponse(responseBody)
    }

    private fun parseTokenResponse(responseBody: String): MsaTokens {
        val json = JSONObject(responseBody)
        val refresh = json.optString("refresh_token", "")
        if (refresh.isBlank()) throw Exception("No refresh_token in response")
        return MsaTokens(
            accessToken = json.getString("access_token"),
            refreshToken = refresh,
            expiresInSec = json.optLong("expires_in", 3600L),
        )
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
