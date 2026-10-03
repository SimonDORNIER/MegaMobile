package com.healthcoach.app

import android.content.Context

data class SyncSnapshot(
    val lastAttemptAt: Long,
    val lastSuccessAt: Long,
    val lastSource: String?,
    val lastError: String?,
    val driveUpdated: Boolean
)

object SyncState {
    private const val PREFS = "healthcoach"
    private const val ATTEMPT = "sync_last_attempt_at"
    private const val SUCCESS = "sync_last_success_at"
    private const val SOURCE = "sync_last_source"
    private const val ERROR = "sync_last_error"
    private const val DRIVE = "sync_last_drive_updated"

    fun attempt(context: Context, source: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(ATTEMPT, System.currentTimeMillis())
            .putString(SOURCE, source)
            .apply()
    }

    fun success(context: Context, source: String, driveUpdated: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(SUCCESS, System.currentTimeMillis())
            .putLong(ATTEMPT, System.currentTimeMillis())
            .putString(SOURCE, source)
            .remove(ERROR)
            .putBoolean(DRIVE, driveUpdated)
            .apply()
    }

    fun failure(context: Context, source: String, message: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(ATTEMPT, System.currentTimeMillis())
            .putString(SOURCE, source)
            .putString(ERROR, message.take(300))
            .putBoolean(DRIVE, false)
            .apply()
    }

    fun read(context: Context): SyncSnapshot {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return SyncSnapshot(
            lastAttemptAt = p.getLong(ATTEMPT, 0L),
            lastSuccessAt = p.getLong(SUCCESS, 0L),
            lastSource = p.getString(SOURCE, null),
            lastError = p.getString(ERROR, null),
            driveUpdated = p.getBoolean(DRIVE, false)
        )
    }
}
