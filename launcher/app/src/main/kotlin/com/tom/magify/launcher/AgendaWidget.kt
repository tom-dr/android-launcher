package com.tom.magify.launcher

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.graphics.Color
import android.graphics.Typeface
import android.provider.CalendarContract
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import com.tom.magify.launcher.databinding.ItemAgendaEventBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

const val AGENDA_WIDGET = "agenda"
// Lighter variant for leisure profiles: only "Prochain événement dans 2 h 15".
const val AGENDA_COUNTDOWN_WIDGET = "agenda_countdown"

private const val AGENDA_WINDOW_MS = 24 * 3600_000L
private const val AGENDA_MAX_ROWS = 4
private val DIM_COLOR = Color.parseColor("#99FFFFFF")
// An event shown as starting in this many minutes or less is flagged in red
// ("dans 10 min" included).
private const val IMMINENT_MINUTES = 10L
private val IMMINENT_COLOR = Color.parseColor("#F87171")

data class AgendaEvent(
    val eventId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val location: String?,
    val color: Int
)

/**
 * The calendar events of the next 24 hours, listed under the profile's apps.
 * Only shown when there is at least one: [events] drives the card's
 * visibility (and thereby the 8-vs-12 apps rule) in MainActivity.
 */
class AgendaWidget(
    private val context: Context,
    private val scope: LifecycleCoroutineScope,
    private val list: LinearLayout,
    private val onEventsChanged: () -> Unit
) {
    var events: List<AgendaEvent> = emptyList()
        private set

    private var refreshJob: Job? = null

    fun hasPermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CALENDAR
    ) == PackageManager.PERMISSION_GRANTED

    /** Requeries off the main thread; [onEventsChanged] only when the list differs. */
    fun refresh() {
        if (!hasPermission()) {
            updateEvents(emptyList())
            return
        }
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val upcoming = withContext(Dispatchers.IO) { runCatching { queryUpcoming() }.getOrDefault(emptyList()) }
            updateEvents(upcoming)
        }
    }

    private fun updateEvents(upcoming: List<AgendaEvent>) {
        if (upcoming == events) return
        events = upcoming
        onEventsChanged()
    }

    private fun queryUpcoming(): List<AgendaEvent> {
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now)
            ContentUris.appendId(it, now + AGENDA_WINDOW_MS)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DISPLAY_COLOR
        )
        val selection = "${CalendarContract.Instances.VISIBLE} = 1" +
            " AND ${CalendarContract.Instances.STATUS} != ${CalendarContract.Events.STATUS_CANCELED}" +
            " AND ${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED}"
        val result = mutableListOf<AgendaEvent>()
        context.contentResolver.query(
            uri, projection, selection, null, "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val allDay = cursor.getInt(4) == 1
                val end = cursor.getLong(3)
                // The window overlaps events that already ended a moment ago
                // (all-day ones are stored in UTC): keep only what's still ahead.
                if (!allDay && end <= now) continue
                result += AgendaEvent(
                    eventId = cursor.getLong(0),
                    title = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: "(Sans titre)",
                    begin = cursor.getLong(2),
                    end = end,
                    allDay = allDay,
                    location = cursor.getString(5)?.takeIf { it.isNotBlank() },
                    color = cursor.getInt(6)
                )
            }
        }
        // Timed events first by start; all-day ones (whole day, no real
        // start time) after those of the same day.
        return result.sortedWith(compareBy({ localDay(it) }, { it.allDay }, { it.begin }))
    }

    /** Whether [profile] shows any agenda form, i.e. whether events must be loaded. */
    fun wantsEvents(profile: ProfileDefinition) =
        AGENDA_WIDGET in profile.widgets || AGENDA_COUNTDOWN_WIDGET in profile.widgets

    /** The full list, which takes the room of an app row. */
    fun showsListFor(profile: ProfileDefinition) = AGENDA_WIDGET in profile.widgets && events.isNotEmpty()

    /** Visible only when enabled for the profile and there's something to show. */
    fun isShownFor(profile: ProfileDefinition) = showsListFor(profile) ||
        (AGENDA_COUNTDOWN_WIDGET in profile.widgets && nextTimedEvent(System.currentTimeMillis()) != null)

    // All-day entries (holidays…) aren't appointments to count down to.
    private fun nextTimedEvent(now: Long) = events.filter { !it.allDay && it.begin > now }.minByOrNull { it.begin }

    // Same rounding down as the minutes displayed, so the red starts exactly
    // when "dans 10 min" appears.
    private fun isImminent(event: AgendaEvent, now: Long) =
        !event.allDay && event.begin > now && (event.begin - now) / 60_000 <= IMMINENT_MINUTES

    fun render(profile: ProfileDefinition) {
        list.removeAllViews()
        val now = System.currentTimeMillis()
        (list.parent as View).setBackgroundResource(
            if (events.any { isImminent(it, now) }) R.drawable.bg_profile_card_alert else R.drawable.bg_profile_card
        )
        if (!showsListFor(profile)) {
            nextTimedEvent(now)?.let { renderCountdown(it, now, underEventRows = false) }
            return
        }
        // During an event, what matters is how long it still lasts and when
        // the next one comes, not the whole list: two lines instead.
        val ongoing = events.filter { !it.allDay && it.begin <= now && it.end > now }
        if (ongoing.isNotEmpty()) {
            ongoing.forEach { event ->
                val left = "reste ${formatDuration((event.end - now) / 60_000)}"
                addEventRow(event, SpannableStringBuilder("Jusqu'à ${hourFormat().format(Date(event.end))} · ").append(
                    left, ForegroundColorSpan(Color.WHITE), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                ))
            }
            nextTimedEvent(now)?.let { renderCountdown(it, now, underEventRows = true) }
            return
        }
        events.take(AGENDA_MAX_ROWS).forEach { event ->
            if (isImminent(event, now)) {
                addEventRow(event, "Dans ${((event.begin - now) / 60_000).coerceAtLeast(1)} min", IMMINENT_COLOR)
            } else {
                addEventRow(event, timeLabel(event, now))
            }
        }
        val inflater = LayoutInflater.from(context)
        val hidden = events.size - AGENDA_MAX_ROWS
        if (hidden > 0) {
            val more = inflater.inflate(R.layout.item_agenda_more, list, false) as TextView
            more.text = context.resources.getQuantityString(R.plurals.agenda_more_events, hidden, hidden)
            more.setOnClickListener { openCalendarApp() }
            list.addView(more)
        }
    }

    private fun addEventRow(event: AgendaEvent, timeText: CharSequence, timeColor: Int? = null) {
        val row = ItemAgendaEventBinding.inflate(LayoutInflater.from(context), list, false)
        row.agendaEventColor.background = colorBar(event)
        row.agendaEventTime.text = timeText
        timeColor?.let(row.agendaEventTime::setTextColor)
        row.agendaEventTitle.text = SpannableStringBuilder(event.title).apply {
            event.location?.let {
                append(" · $it", ForegroundColorSpan(DIM_COLOR), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        row.root.setOnClickListener { openEvent(event) }
        list.addView(row.root)
    }

    private fun colorBar(event: AgendaEvent) = GradientDrawable().apply {
        cornerRadius = 2 * context.resources.displayMetrics.density
        setColor(event.color or 0xFF000000.toInt())
    }

    private fun hourFormat() = SimpleDateFormat("HH:mm", Locale.FRENCH)

    private fun formatDuration(minutes: Long): String {
        val m = minutes.coerceAtLeast(1)
        return if (m < 60) "$m min" else "${m / 60} h %02d".format(m % 60)
    }

    /**
     * "Prochain événement dans …" on its own (leisure profiles) or under the
     * ongoing event's row, then indented to line up with its text.
     */
    private fun renderCountdown(event: AgendaEvent, now: Long, underEventRows: Boolean) {
        val minutes = ((event.begin - now) / 60_000).coerceAtLeast(1)
        // A countdown reads well for the next few hours; beyond 12 h the
        // actual time ("demain à 01:00") is more telling.
        val (prefix, value) = when {
            minutes < 12 * 60 -> "Prochain événement dans " to formatDuration(minutes)
            else -> {
                val day = if (localDay(event.begin) == localDay(now)) "aujourd'hui" else "demain"
                "Prochain événement " to "$day à ${hourFormat().format(Date(event.begin))}"
            }
        }
        val imminent = isImminent(event, now)
        val text = SpannableStringBuilder(prefix).append(
            value,
            if (imminent) StyleSpan(Typeface.BOLD) else ForegroundColorSpan(Color.WHITE),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        if (underEventRows) {
            // Same row as the events above (color bar included), the whole
            // sentence in the title slot.
            val row = ItemAgendaEventBinding.inflate(LayoutInflater.from(context), list, false)
            row.agendaEventColor.background = colorBar(event)
            row.agendaEventTime.visibility = View.GONE
            row.agendaEventTitle.text = text
            row.agendaEventTitle.setTextColor(if (imminent) IMMINENT_COLOR else DIM_COLOR)
            row.agendaEventTitle.textSize = 14f
            row.root.setOnClickListener { openEvent(event) }
            list.addView(row.root)
            return
        }
        val row = LayoutInflater.from(context).inflate(R.layout.item_agenda_more, list, false) as TextView
        row.text = text
        if (imminent) row.setTextColor(IMMINENT_COLOR)
        row.textSize = 15f
        row.setPadding(row.paddingTop * 2, row.paddingTop, row.paddingRight, row.paddingBottom)
        row.setOnClickListener { openEvent(event) }
        list.addView(row)
    }

    private fun timeLabel(event: AgendaEvent, now: Long): String {
        val day = when (localDay(event) - localDay(now)) {
            0 -> null
            1 -> "Demain"
            else -> SimpleDateFormat("EEEE", Locale.FRENCH).format(Date(event.begin))
        }
        if (event.allDay) return listOfNotNull(day ?: "Aujourd'hui", "toute la journée").joinToString(", ")
        val hours = SimpleDateFormat("HH:mm", Locale.FRENCH)
        if (event.begin <= now) return "En cours, jusqu'à ${hours.format(Date(event.end))}"
        val range = "${hours.format(Date(event.begin))} – ${hours.format(Date(event.end))}"
        return listOfNotNull(day, range).joinToString(" ")
    }

    // Days since epoch in local time; all-day events are stored at UTC
    // midnight, so their day is read in UTC to land on the right date.
    private fun localDay(event: AgendaEvent): Int =
        if (event.allDay) dayIndex(event.begin, TimeZone.getTimeZone("UTC")) else localDay(event.begin)

    private fun localDay(millis: Long): Int = dayIndex(millis, TimeZone.getDefault())

    private fun dayIndex(millis: Long, zone: TimeZone): Int =
        ((millis + zone.getOffset(millis)) / 86_400_000L).toInt()

    private fun openEvent(event: AgendaEvent) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setData(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { openCalendarApp() }
    }

    private fun openCalendarApp() {
        runCatching {
            context.startActivity(
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
