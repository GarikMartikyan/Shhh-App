package com.shhh

import android.content.Context
import org.json.JSONArray
import java.util.Calendar

/**
 * A record of completed silences, so the screen can show that Shhh works while you are not
 * watching it -- which is the only time it ever does anything.
 */
object History {

    data class Entry(val startMs: Long, val durationMs: Long)

    private const val KEY = "history"
    private const val MAX_ENTRIES = 60

    /** Anything shorter than this is a fumble, not a silence, and only clutters the list. */
    private const val MIN_DURATION_MS = 2_000L

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("shhh", Context.MODE_PRIVATE)

    fun add(ctx: Context, startMs: Long, durationMs: Long) {
        if (durationMs < MIN_DURATION_MS) return
        val entries = (all(ctx) + Entry(startMs, durationMs)).takeLast(MAX_ENTRIES)
        val json = JSONArray()
        entries.forEach { json.put(JSONArray().put(it.startMs).put(it.durationMs)) }
        prefs(ctx).edit().putString(KEY, json.toString()).apply()
    }

    fun all(ctx: Context): List<Entry> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            (0 until json.length()).map { i ->
                val pair = json.getJSONArray(i)
                Entry(pair.getLong(0), pair.getLong(1))
            }
        }.getOrDefault(emptyList())
    }

    /** Newest first, today only. */
    fun today(ctx: Context): List<Entry> {
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return all(ctx).filter { it.startMs >= startOfDay }.sortedByDescending { it.startMs }
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
    }
}
