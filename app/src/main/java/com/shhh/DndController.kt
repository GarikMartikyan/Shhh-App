package com.shhh

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.util.Log

/**
 * Owns exactly one AutomaticZenRule and toggles it.
 *
 * Since Android 15, an app cannot set the device's global Do Not Disturb state -- calls to
 * setInterruptionFilter create an implicit rule instead, and an app may only clear a rule it owns.
 * Owning both the on and the off transition here is what keeps face-up reliably un-silencing.
 *
 * The rule is created with no ZenPolicy, so it inherits whatever Do Not Disturb configuration the
 * user already has (starred contacts, repeat callers, alarm exceptions). That is what Pixel's Flip
 * to Shhh does.
 */
object DndController {

    private const val TAG = "Shhh"
    private const val RULE_NAME = "Shhh (face down)"
    private val CONDITION_ID: Uri = Uri.parse("condition://com.shhh/facedown")

    private fun nm(ctx: Context) =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun hasAccess(ctx: Context): Boolean = nm(ctx).isNotificationPolicyAccessGranted

    /**
     * Returns the id of our rule, creating it if the user deleted it or this is a first run.
     * Null if Do Not Disturb access has not been granted yet.
     */
    private fun ruleId(ctx: Context): String? {
        val nm = nm(ctx)
        if (!nm.isNotificationPolicyAccessGranted) return null

        val prefs = Prefs(ctx)
        val existing = prefs.ruleId
        if (existing != null && nm.automaticZenRules.containsKey(existing)) return existing

        // Recover a rule created by a previous install before minting a duplicate.
        nm.automaticZenRules.entries
            .firstOrNull { it.value.conditionId == CONDITION_ID }
            ?.let {
                prefs.ruleId = it.key
                return it.key
            }

        return try {
            val builder = AutomaticZenRule.Builder(RULE_NAME, CONDITION_ID)
                .setConfigurationActivity(ComponentName(ctx, MainActivity::class.java))
                .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                .setEnabled(true)
            if (Build.VERSION.SDK_INT >= 35) {
                builder.setType(AutomaticZenRule.TYPE_OTHER)
            }
            val id = nm.addAutomaticZenRule(builder.build())
            prefs.ruleId = id
            Log.i(TAG, "created zen rule $id")
            id
        } catch (t: Throwable) {
            Log.e(TAG, "could not create zen rule", t)
            null
        }
    }

    fun ensureRule(ctx: Context) {
        ruleId(ctx)
    }

    fun setEngaged(ctx: Context, on: Boolean) {
        val id = ruleId(ctx) ?: run {
            Log.w(TAG, "no zen rule; DND access missing?")
            return
        }
        val condition = Condition(
            CONDITION_ID,
            if (on) "Face down" else "Face up",
            if (on) Condition.STATE_TRUE else Condition.STATE_FALSE,
        )
        try {
            nm(ctx).setAutomaticZenRuleState(id, condition)
            Log.i(TAG, "zen rule $id -> ${if (on) "ON" else "OFF"}")
        } catch (t: Throwable) {
            Log.e(TAG, "could not set zen rule state", t)
        }
    }

    /** True when our own rule is currently active. */
    fun isEngaged(ctx: Context): Boolean {
        val nm = nm(ctx)
        if (!nm.isNotificationPolicyAccessGranted) return false
        return nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }
}
