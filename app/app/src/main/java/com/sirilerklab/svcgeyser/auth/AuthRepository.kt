package com.sirilerklab.svcgeyser.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class StoredAuth(
    val refreshToken: String,
    val xuid: String,
    val expiresAtMs: Long,
)

class AuthRepository(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "svcgeyser_auth",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load(): StoredAuth? {
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return null
        val xuid = prefs.getString(KEY_XUID, null) ?: return null
        val expires = prefs.getLong(KEY_EXPIRES, 0L)
        return StoredAuth(refresh, xuid, expires)
    }

    fun save(refreshToken: String, xuid: String, expiresAtMs: Long) {
        prefs.edit()
            .putString(KEY_REFRESH, refreshToken)
            .putString(KEY_XUID, xuid)
            .putLong(KEY_EXPIRES, expiresAtMs)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_XUID = "xuid"
        private const val KEY_EXPIRES = "expires_at_ms"
    }
}
