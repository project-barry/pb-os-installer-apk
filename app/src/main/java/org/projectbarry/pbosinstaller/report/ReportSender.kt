package org.projectbarry.pbosinstaller.report

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends the device report to the relay, at most once a day per install: the
 * relay has its own limits, this one keeps people from tapping it repeatedly.
 */
class ReportSender(context: Context, private val url: String, private val appVersion: String) {
    enum class Result { SENT, DUPLICATE, LIMIT, FAILED }

    private val prefs = context.getSharedPreferences("report", Context.MODE_PRIVATE)

    /** True while a report sent from this install is under a day old. */
    fun sentRecently(now: Long = System.currentTimeMillis()): Boolean =
        now - prefs.getLong("sent_at", 0L) in 0 until COOLDOWN_MS

    /** Blocking; call off the main thread. */
    fun send(fields: Map<String, String>): Result {
        val result = try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("User-Agent", "PB-OS-Installer/$appVersion")
                conn.outputStream.use { it.write(JSONObject(fields).toString().toByteArray()) }
                val code = conn.responseCode
                val body = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
                when (runCatching { JSONObject(body).getString("status") }.getOrNull()) {
                    "sent" -> Result.SENT
                    "duplicate" -> Result.DUPLICATE
                    "limit" -> Result.LIMIT
                    else -> Result.FAILED
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Result.FAILED
        }
        // A report the relay already has counts as sent: no point repeating it.
        if (result == Result.SENT || result == Result.DUPLICATE) {
            prefs.edit().putLong("sent_at", System.currentTimeMillis()).apply()
        }
        return result
    }

    private companion object {
        const val COOLDOWN_MS = 24L * 60 * 60 * 1000
    }
}
