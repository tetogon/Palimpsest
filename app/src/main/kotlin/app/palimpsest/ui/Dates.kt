package app.palimpsest.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal val zone: ZoneId get() = ZoneId.systemDefault()

internal fun day(epochDay: Int?, fallbackMs: Long): LocalDate =
    epochDay?.let { LocalDate.ofEpochDay(it.toLong()) } ?: Instant.ofEpochMilli(fallbackMs).atZone(zone).toLocalDate()

/** Today: time; this year: "12 Oct"; older: medium date. */
internal fun shortWhen(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(zone)
    val today = LocalDate.now(zone)
    val pattern = when {
        t.toLocalDate() == today -> DateTimeFormatter.ofPattern("HH:mm")
        t.year == today.year -> DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    }
    return t.format(pattern)
}

internal fun String.capitalized() = replaceFirstChar { it.titlecase(Locale.getDefault()) }
