package com.xlonlabs.iitdcompanion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class DropStatus(val drop: Boolean, val dropKey: String)

object WorkerTracker {
    private const val CHECKIN_URL = "https://iitd-companion-worker.hellonitinbro.workers.dev/api/checkin"
    private const val DROP_STATUS_URL = "https://iitd-companion-worker.hellonitinbro.workers.dev/api/perks-drop"

    var dropStatus by mutableStateOf<DropStatus?>(null)
        private set

    fun checkin(kerberos: String) {
        val id = kerberos.trim().lowercase()
        if (id.isBlank() || id == "demo") {
            refreshDropStatus()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val conn = URL(CHECKIN_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) IITDCompanion")
                val json = """{"kerberos_id":"$id","platform":"android"}"""
                conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    parseAndUpdateDrop(body)
                }
            }
        }
    }

    fun refreshDropStatus() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val conn = URL("$DROP_STATUS_URL?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
                conn.useCaches = false
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) IITDCompanion")
                conn.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    parseAndUpdateDrop(body)
                }
            }
        }
    }

    private suspend fun parseAndUpdateDrop(body: String) {
        val obj = JSONObject(body)
        if (!obj.has("drop")) return
        val status = DropStatus(
            drop = obj.optBoolean("drop", false),
            dropKey = obj.optString("dropKey", ""),
        )
        withContext(Dispatchers.Main) {
            dropStatus = status
        }
    }
}
