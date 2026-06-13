package com.sirilerklab.svcgeyser.auth

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

private const val CLIENT_ID    = "fdcde701-35a0-4e25-bf8c-3d66b0985f57"
private const val REDIRECT_URI = "svcgeyser://auth"
private const val AUTH_URL     = "https://login.live.com/oauth20_authorize.srf"
private const val TOKEN_URL    = "https://login.live.com/oauth20_token.srf"
private const val SCOPES       = "XboxLive.signin offline_access"
private const val AUTH_TIMEOUT = 5 * 60 * 1_000L

object LiveOAuthHelper {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun signIn(activity: Activity): String {
        val verifier  = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)

        val authUri = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id",             CLIENT_ID)
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

        // Must run on IO — blocking OkHttp call.
        return withContext(Dispatchers.IO) { exchangeCode(code, verifier) }
    }

    private fun exchangeCode(code: String, verifier: String): String {
        val body = FormBody.Builder()
            .add("grant_type",    "authorization_code")
            .add("client_id",    CLIENT_ID)
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

        Log.d(TAG, "Token exchange OK — got access_token")
        return JSONObject(responseBody).getString("access_token")
    }

    // ---- PKCE -----------------------------------------------------------

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
