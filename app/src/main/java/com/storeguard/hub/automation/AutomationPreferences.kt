package com.storeguard.hub.automation

import android.content.Context

object AutomationPreferences {
    private const val PREFS = "storeguard_automation"
    private const val KEY_POS_ACTIVE = "pos_auto_active"
    private const val KEY_SESSION_ID = "pos_session_id"
    private const val KEY_STARTED_AT = "pos_started_at"

    fun startPosSession(context: Context): Long {
        val id = System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_POS_ACTIVE, true)
            .putLong(KEY_SESSION_ID, id)
            .putLong(KEY_STARTED_AT, id)
            .apply()
        return id
    }

    fun stopPosSession(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_POS_ACTIVE, false)
            .apply()
    }

    fun isPosSessionActive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_POS_ACTIVE, false)

    fun sessionId(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_SESSION_ID, 0L)

    fun startedAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_STARTED_AT, 0L)
}
