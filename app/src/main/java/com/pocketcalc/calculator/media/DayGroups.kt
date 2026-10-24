package com.pocketcalc.calculator.media

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Снимки одного дня: как в Галерее — заголовок с датой и фото под ним. */
data class DayGroup<T>(val day: LocalDate, val items: List<T>)

/**
 * Делит список, уже отсортированный от новых к старым, на дни (по часовому
 * поясу [zone]). Порядок внутри дня сохраняется.
 */
fun <T> groupByDay(items: List<T>, zone: ZoneId, timeOf: (T) -> Long): List<DayGroup<T>> {
    val result = ArrayList<DayGroup<T>>()
    var currentDay: LocalDate? = null
    var current = ArrayList<T>()
    for (item in items) {
        val day = Instant.ofEpochMilli(timeOf(item)).atZone(zone).toLocalDate()
        if (day != currentDay) {
            if (currentDay != null) result += DayGroup(currentDay, current)
            currentDay = day
            current = ArrayList()
        }
        current += item
    }
    if (currentDay != null) result += DayGroup(currentDay, current)
    return result
}
