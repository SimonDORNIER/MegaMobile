package com.healthcoach.app

import android.content.Context

data class SyncSnapshot(
    val lastAttemptAt: Long,
    val lastHealthSuccessAt: Long,
    val lastDriveSuccessAt: Long,
    val lastSource: String?,
    val lastError: String?
)

object SyncState {
    private const val PREFS = "healthcoach"

    fun recordAttempt(context: Context, source: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong("last_sync_attempt_at", System.currentTimeMillis())
            .putString("last_sync_source", source)
            .apply()
    }

    fun recordSuccess(context: Context, driveWritten: Boolean, source: String) {
        val now = System.currentTimeMillis()
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong("last_sync_attempt_at", now)
            .putLong("last_health_sync_at", now)
            .putString("last_sync_source", source)
            .remove("last_sync_error")

        if (driveWritten) editor.putLong("last_drive_sync_at", now)
        editor.apply()
    }

    fun recordFailure(context: Context, message: String, source: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong("last_sync_attempt_at", System.currentTimeMillis())
            .putString("last_sync_source", source)
            .putString("last_sync_error", message.take(300))
            .apply()
    }

    fun read(context: Context): SyncSnapshot {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return SyncSnapshot(
            lastAttemptAt = p.getLong("last_sync_attempt_at", 0L),
            lastHealthSuccessAt = p.getLong("last_health_sync_at", 0L),
            lastDriveSuccessAt = p.getLong("last_drive_sync_at", 0L),
            lastSource = p.getString("last_sync_source", null),
            lastError = p.getString("last_sync_error", null)
        )
    }
}
