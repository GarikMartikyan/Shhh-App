package com.shhh

import android.content.Context

class Prefs(ctx: Context) {

    private val sp = ctx.applicationContext
        .getSharedPreferences("shhh", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    var ruleId: String?
        get() = sp.getString(KEY_RULE_ID, null)
        set(v) = sp.edit().putString(KEY_RULE_ID, v).apply()

    var sensitivity: Sensitivity
        get() = Sensitivity.fromName(sp.getString(KEY_SENSITIVITY, null))
        set(v) = sp.edit().putString(KEY_SENSITIVITY, v.name).apply()

    /**
     * Android requires *a* notification for any foreground service, so this cannot suppress it
     * outright -- it strips it to the minimum and defers it instead.
     */
    var showNotification: Boolean
        get() = sp.getBoolean(KEY_SHOW_NOTIFICATION, true)
        set(v) = sp.edit().putBoolean(KEY_SHOW_NOTIFICATION, v).apply()

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_RULE_ID = "rule_id"
        const val KEY_SENSITIVITY = "sensitivity"
        const val KEY_SHOW_NOTIFICATION = "show_notification"
    }
}
