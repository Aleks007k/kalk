package com.pocketcalc.calculator.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class DayGroupsTest {

    private val zone = ZoneId.of("Europe/Kyiv")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test fun emptyListHasNoDays() {
        assertTrue(groupByDay(emptyList<Long>(), zone) { it }.isEmpty())
    }

    @Test fun splitsNewestFirstListIntoDays() {
        val times = listOf(
            at(2026, 10, 24, 18), at(2026, 10, 24, 9),
            at(2026, 10, 23, 23, 59),
            at(2025, 12, 31, 12),
        )
        val days = groupByDay(times, zone) { it }
        assertEquals(
            listOf(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 23), LocalDate.of(2025, 12, 31)),
            days.map { it.day },
        )
        // Порядок внутри дня не меняется.
        assertEquals(listOf(times[0], times[1]), days[0].items)
        assertEquals(1, days[1].items.size)
    }

    @Test fun dayBoundaryFollowsLocalTime() {
        // 00:30 по Киеву — это ещё предыдущий день по UTC, но в Галерее — новый день.
        val justAfterMidnight = at(2026, 10, 24, 0, 30)
        val lateEvening = at(2026, 10, 23, 23, 30)
        val days = groupByDay(listOf(justAfterMidnight, lateEvening), zone) { it }
        assertEquals(listOf(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 23)), days.map { it.day })
    }
}
