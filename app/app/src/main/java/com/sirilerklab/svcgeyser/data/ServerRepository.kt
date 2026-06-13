package com.sirilerklab.svcgeyser.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedServer(
    val id: String,
    val label: String,
    val host: String,
    val port: Int,
    val lastUsed: Long = 0L,
)

private val Context.serverDataStore: DataStore<Preferences> by preferencesDataStore("servers")

class ServerRepository(private val context: Context) {

    private val key = stringPreferencesKey("servers_json")

    val servers: Flow<List<SavedServer>> = context.serverDataStore.data.map { prefs ->
        parse(prefs[key] ?: "[]")
    }

    suspend fun list(): List<SavedServer> = servers.first()

    suspend fun add(label: String, host: String, port: Int): SavedServer {
        val server = SavedServer(
            id = UUID.randomUUID().toString(),
            label = label.trim(),
            host = host.trim(),
            port = port,
        )
        context.serverDataStore.edit { prefs ->
            val current = parse(prefs[key] ?: "[]").toMutableList()
            current.add(server)
            prefs[key] = serialize(current)
        }
        return server
    }

    suspend fun update(server: SavedServer) {
        context.serverDataStore.edit { prefs ->
            val current = parse(prefs[key] ?: "[]").map {
                if (it.id == server.id) server else it
            }
            prefs[key] = serialize(current)
        }
    }

    suspend fun delete(id: String) {
        context.serverDataStore.edit { prefs ->
            val current = parse(prefs[key] ?: "[]").filter { it.id != id }
            prefs[key] = serialize(current)
        }
    }

    suspend fun markLastUsed(id: String) {
        context.serverDataStore.edit { prefs ->
            val now = System.currentTimeMillis()
            val current = parse(prefs[key] ?: "[]").map {
                if (it.id == id) it.copy(lastUsed = now) else it
            }
            prefs[key] = serialize(current)
        }
    }

    private fun parse(json: String): List<SavedServer> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SavedServer(
                id = o.getString("id"),
                label = o.getString("label"),
                host = o.getString("host"),
                port = o.getInt("port"),
                lastUsed = o.optLong("lastUsed", 0L),
            )
        }.sortedByDescending { it.lastUsed }
    }

    private fun serialize(servers: List<SavedServer>): String {
        val arr = JSONArray()
        servers.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("label", s.label)
                put("host", s.host)
                put("port", s.port)
                put("lastUsed", s.lastUsed)
            })
        }
        return arr.toString()
    }
}
