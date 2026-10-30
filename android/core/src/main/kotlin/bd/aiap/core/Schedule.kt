package bd.aiap.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Next due time for a repeating reminder, computed in local time so it keeps the wall-clock hour. */
fun nextOccurrence(dueAtMillis: Long, repeat: Repeat, zone: ZoneId): Long {
    val local = Instant.ofEpochMilli(dueAtMillis).atZone(zone)
    val next: ZonedDateTime = when (repeat) {
        Repeat.NONE -> return dueAtMillis
        Repeat.DAILY -> local.plusDays(1)
        Repeat.WEEKLY -> local.plusWeeks(1)
        Repeat.MONTHLY -> local.plusMonths(1) // clamps the 31st to the month's last day
        Repeat.YEARLY -> local.plusYears(1)
    }
    return next.toInstant().toEpochMilli()
}

/** Next occurrence strictly after [nowMillis], skipping any missed while the phone was off. */
fun nextFutureOccurrence(dueAtMillis: Long, repeat: Repeat, zone: ZoneId, nowMillis: Long): Long {
    var next = nextOccurrence(dueAtMillis, repeat, zone)
    while (next <= nowMillis) next = nextOccurrence(next, repeat, zone)
    return next
}

/** Consecutive days the habit was done, ending today (or yesterday, if today is not done yet). */
fun streak(doneDates: Set<String>, today: java.time.LocalDate): Int {
    var day = if (today.toString() in doneDates) today else today.minusDays(1)
    var count = 0
    while (day.toString() in doneDates) {
        count++
        day = day.minusDays(1)
    }
    return count
}
