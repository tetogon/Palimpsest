package app.palimpsest.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.palimpsest.R
import app.palimpsest.ffi.EntrySummary
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The diary as a month: days with entries are marked; the chosen day's entries are listed
 * below, with the same day of earlier years. [day] is the chosen day, also used by "+".
 */
@Composable
internal fun DiaryCalendar(
    items: List<EntrySummary>,
    picker: Picker,
    day: LocalDate,
    onDay: (LocalDate) -> Unit,
    onWrite: (LocalDate) -> Unit,
) {
    var month by remember { mutableStateOf(YearMonth.from(day)) }
    val byDay = remember(items) { items.groupBy { day(it.diaryDate, it.createdMs) } }
    val chosen = byDay[day].orEmpty().sortedByDescending { it.createdMs }
    val earlier = byDay.filterKeys { it.year < day.year && it.monthValue == day.monthValue && it.dayOfMonth == day.dayOfMonth }
        .toSortedMap(compareByDescending { it }).values.flatten()
    val locale = Locale.getDefault()
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
        item(key = "month") {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minusMonths(1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_month))
                }
                Text(
                    month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)).capitalized(),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clickable {
                        month = YearMonth.now()
                        onDay(LocalDate.now())
                    },
                )
                IconButton(onClick = { month = month.plusMonths(1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_month))
                }
            }
        }
        item(key = "grid") { MonthGrid(month, byDay.keys, day, onDay, locale) }
        item(key = "day") {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    day.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale)).capitalized(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (chosen.isNotEmpty()) {
                    TextButton(onClick = { onWrite(day) }) { Text(stringResource(R.string.write_more)) }
                }
            }
        }
        if (chosen.isEmpty()) {
            item(key = "none") {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.no_entries_that_day), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { onWrite(day) }) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.write_that_day))
                    }
                }
            }
        }
        items(chosen, key = { it.entryId }) { e -> DiaryRow(e, picker) }
        if (earlier.isNotEmpty()) {
            item(key = "earlier") {
                Text(
                    stringResource(R.string.on_this_day),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                )
            }
            items(earlier, key = { "e" + it.entryId }) { e ->
                Column {
                    Text(
                        day(e.diaryDate, e.createdMs).year.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                    DiaryRow(e, picker)
                }
            }
        }
    }
}

@Composable
private fun MonthGrid(month: YearMonth, marked: Set<LocalDate>, chosen: LocalDate, onDay: (LocalDate) -> Unit, locale: Locale) {
    val firstDay = WeekFields.of(locale).firstDayOfWeek
    val lead = (month.atDay(1).dayOfWeek.value - firstDay.value + 7) % 7
    val today = LocalDate.now()
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until 7) {
                Text(
                    firstDay.plus(i.toLong()).getDisplayName(TextStyle.SHORT_STANDALONE, locale).uppercase(locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                )
            }
        }
        val cells = lead + month.lengthOfMonth()
        for (week in 0 until (cells + 6) / 7) {
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    val n = week * 7 + i - lead + 1
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(3.dp), contentAlignment = Alignment.Center) {
                        if (n in 1..month.lengthOfMonth()) {
                            val date = month.atDay(n)
                            val isChosen = date == chosen
                            val has = date in marked
                            Surface(
                                shape = CircleShape,
                                color = when {
                                    isChosen -> colors.primary
                                    has -> colors.secondaryContainer
                                    else -> colors.surface.copy(alpha = 0f)
                                },
                                border = if (date == today && !isChosen) BorderStroke(1.5.dp, colors.primary) else null,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape).clickable { onDay(date) },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        n.toString(),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (has || date == today) FontWeight.SemiBold else FontWeight.Normal,
                                        color = when {
                                            isChosen -> colors.onPrimary
                                            has -> colors.onSecondaryContainer
                                            else -> colors.onSurface
                                        },
                                    )
                                    if (has) {
                                        Box(
                                            Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp).size(4.dp)
                                                .background(if (isChosen) colors.onPrimary else colors.primary, CircleShape),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
