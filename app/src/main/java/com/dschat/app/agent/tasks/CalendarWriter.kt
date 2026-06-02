package com.dschat.app.agent.tasks

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.TimeZone

/**
 * Writes calendar events DIRECTLY via ContentResolver (no system Calendar UI). Shared by the
 * create_calendar_event tool and the ambient auto-schedule pipeline.
 *
 * Gotchas baked in here:
 *  - EVENT_TIMEZONE and DTEND are mandatory or the insert throws.
 *  - Timed events use local epoch-ms directly; ALL-DAY events must be stored at UTC midnight with
 *    DTEND exclusive (+1 day) and EVENT_TIMEZONE="UTC" — mixing the two yields wrong-day events.
 *  - Querying Calendars needs READ_CALENDAR; inserting needs WRITE_CALENDAR.
 *  - Direct insert has no system de-dup → callers should findDuplicate() first.
 */
object CalendarWriter {

    private fun hasPerm(ctx: Context, p: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun canWrite(ctx: Context): Boolean =
        hasPerm(ctx, Manifest.permission.WRITE_CALENDAR) && hasPerm(ctx, Manifest.permission.READ_CALENDAR)

    /** Pick a writable, visible calendar (prefer the primary/owner account). null if none. */
    fun pickWritableCalendarId(ctx: Context): Long? {
        if (!hasPerm(ctx, Manifest.permission.READ_CALENDAR)) return null
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.OWNER_ACCOUNT
        )
        return try {
            ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
                var best: Long? = null
                var bestScore = -1
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val access = c.getInt(1)
                    val visible = c.getInt(2) == 1
                    val isPrimary = c.getInt(3) == 1
                    val account = c.getString(4)
                    val owner = c.getString(5)
                    if (access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                    if (!visible) continue
                    var score = 1
                    if (isPrimary || (account != null && account == owner)) score += 2
                    if (score > bestScore) {
                        bestScore = score; best = id
                    }
                }
                best
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Insert an event; returns the new event id or null on failure. */
    fun insertEvent(
        ctx: Context,
        title: String,
        detail: String?,
        beginMs: Long,
        endMs: Long,
        allDay: Boolean,
        calendarId: Long
    ): Long? {
        if (!canWrite(ctx)) return null
        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                if (!detail.isNullOrBlank()) put(CalendarContract.Events.DESCRIPTION, detail)
                if (allDay) {
                    val utcStart = utcMidnight(beginMs)
                    put(CalendarContract.Events.DTSTART, utcStart)
                    put(CalendarContract.Events.DTEND, utcStart + 86_400_000L) // exclusive end
                    put(CalendarContract.Events.ALL_DAY, 1)
                    put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                } else {
                    put(CalendarContract.Events.DTSTART, beginMs)
                    put(CalendarContract.Events.DTEND, if (endMs > beginMs) endMs else beginMs + 3_600_000L)
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                }
            }
            ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)?.let { ContentUris.parseId(it) }
        } catch (e: Exception) {
            null
        }
    }

    /** Add an alert reminder N minutes before the event (best-effort). */
    fun addReminderMinutes(ctx: Context, eventId: Long, minutesBefore: Int) {
        try {
            val v = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, minutesBefore)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, v)
        } catch (e: Exception) {
            // best-effort
        }
    }

    fun deleteEvent(ctx: Context, eventId: Long): Boolean = try {
        ctx.contentResolver.delete(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null
        ) > 0
    } catch (e: Exception) {
        false
    }

    /** True if an event with the same title + exact start already exists (avoid duplicates). */
    fun findDuplicate(ctx: Context, title: String, beginMs: Long): Boolean {
        if (!hasPerm(ctx, Manifest.permission.READ_CALENDAR)) return false
        return try {
            ctx.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID),
                "${CalendarContract.Events.TITLE}=? AND ${CalendarContract.Events.DTSTART}=?",
                arrayOf(title, beginMs.toString()),
                null
            )?.use { it.count > 0 } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun utcMidnight(ms: Long): Long {
        val local = Calendar.getInstance().apply { timeInMillis = ms }
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        }.timeInMillis
    }
}
