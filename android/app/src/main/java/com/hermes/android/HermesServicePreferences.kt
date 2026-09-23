package com.hermes.android

import android.content.Context

/** Minimal durable state needed to recover the background runtime after reboot. */
class HermesServicePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("hermes_service", Context.MODE_PRIVATE)

    var serviceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()

    var lastSuccessfulStartEpochMs: Long
        get() = prefs.getLong(KEY_LAST_SUCCESS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SUCCESS, value).apply()

    var lastStatus: String
        get() = prefs.getString(KEY_LAST_STATUS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_STATUS, value).apply()

    companion object {
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_LAST_SUCCESS = "last_successful_start_epoch_ms"
        private const val KEY_LAST_STATUS = "last_status"
    }
}
