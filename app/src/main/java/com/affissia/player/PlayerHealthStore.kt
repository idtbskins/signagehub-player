package com.affissia.player

import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter

class PlayerHealthStore(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val launchCount: Int
        get() = preferences.getInt(KEY_LAUNCH_COUNT, 0)

    val crashCount: Int
        get() = preferences.getInt(KEY_CRASH_COUNT, 0)

    val sessionId: String
        get() = preferences.getString(KEY_SESSION_ID, "").orEmpty()

    val lastCrashJson: String
        get() = preferences.getString(KEY_LAST_CRASH_JSON, "").orEmpty()

    val lastCrashSummary: String
        get() = preferences.getString(KEY_LAST_CRASH_SUMMARY, "").orEmpty()

    fun recordLaunch() {
        val nextLaunchCount = launchCount + 1
        val nextSessionId = buildSessionId(nextLaunchCount)
        preferences.edit()
            .putInt(KEY_LAUNCH_COUNT, nextLaunchCount)
            .putString(KEY_SESSION_ID, nextSessionId)
            .apply()
    }

    fun recordCrash(threadName: String?, throwable: Throwable) {
        val summary = crashSummary(throwable)
        val crashJson = JSONObject().apply {
            put("timestamp_ms", System.currentTimeMillis())
            put("session_id", sessionId)
            put("thread", threadName ?: JSONObject.NULL)
            put("type", throwable.javaClass.name)
            put("message", throwable.message ?: JSONObject.NULL)
            put("summary", summary)
            put("stacktrace", stackTraceString(throwable))
        }.toString()

        preferences.edit()
            .putInt(KEY_CRASH_COUNT, crashCount + 1)
            .putString(KEY_LAST_CRASH_SUMMARY, summary)
            .putString(KEY_LAST_CRASH_JSON, crashJson)
            .commit()
    }

    fun healthJson(): JSONObject {
        return JSONObject().apply {
            put("session_id", sessionId)
            put("launch_count", launchCount)
            put("crash_count", crashCount)
            put("last_crash_summary", lastCrashSummary.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            putLastCrashJson(this)
        }
    }

    fun putLastCrashJson(target: JSONObject) {
        val raw = lastCrashJson
        if (raw.isBlank()) {
            target.put("last_crash_json", JSONObject.NULL)
            return
        }
        val parsed = try {
            JSONObject(raw)
        } catch (e: Exception) {
            null
        }
        target.put("last_crash_json", parsed ?: raw)
    }

    private fun buildSessionId(launchCount: Int): String {
        return "session_${System.currentTimeMillis()}_${Process.myPid()}_$launchCount"
    }

    private fun crashSummary(throwable: Throwable): String {
        val message = throwable.message.orEmpty()
        val raw = if (message.isBlank()) {
            throwable.javaClass.name
        } else {
            "${throwable.javaClass.name}: $message"
        }
        return raw.take(MAX_SUMMARY_CHARS)
    }

    private fun stackTraceString(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString().take(MAX_STACKTRACE_CHARS)
    }

    companion object {
        private const val PREFS_NAME = "affissia_player_health"
        private const val KEY_LAUNCH_COUNT = "launch_count"
        private const val KEY_CRASH_COUNT = "crash_count"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_LAST_CRASH_JSON = "last_crash_json"
        private const val KEY_LAST_CRASH_SUMMARY = "last_crash_summary"
        private const val MAX_SUMMARY_CHARS = 240
        private const val MAX_STACKTRACE_CHARS = 12_000
    }
}
