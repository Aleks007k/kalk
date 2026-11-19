package com.pocketcalc.calculator.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttemptGuardTest {

    private class MemoryStore : AttemptGuard.Store {
        override var failures = 0
        override var skipped = 0
        override var lastCheckAt = 0L
    }

    private var clock = 1_000_000L
    private val store = MemoryStore()
    private val guard = AttemptGuard(store) { clock }

    private fun failOnce() {
        assertTrue(guard.tryBeginCheck())
        guard.onFailure()
    }

    @Test fun firstFiveWrongAttemptsAreCheckedAtOnce() {
        repeat(AttemptGuard.FREE_FAILURES) { failOnce() }
        // Шестая — сразу после пятой — уже не проверяется.
        assertFalse(guard.tryBeginCheck())
    }

    @Test fun afterFiveFailuresOneCheckPer30Seconds() {
        repeat(5) { failOnce() }
        clock += 29_000
        assertFalse(guard.tryBeginCheck())
        clock += 1_000
        assertTrue(guard.tryBeginCheck())
        guard.onFailure()
        assertFalse(guard.tryBeginCheck())
    }

    @Test fun pauseGrowsWithFailures() {
        assertEquals(0L, AttemptGuard.pauseAfter(4))
        assertEquals(30_000L, AttemptGuard.pauseAfter(5))
        assertEquals(300_000L, AttemptGuard.pauseAfter(10))
        assertEquals(1_800_000L, AttemptGuard.pauseAfter(20))
        assertEquals(1_800_000L, AttemptGuard.pauseAfter(500))
    }

    @Test fun skippedAttemptsDoNotExtendThePause() {
        repeat(5) { failOnce() }
        // Кто-то тыкает каждые 10 секунд — проверка всё равно случится через 30 секунд.
        repeat(2) {
            clock += 10_000
            assertFalse(guard.tryBeginCheck())
        }
        clock += 10_000
        assertTrue(guard.tryBeginCheck())
    }

    @Test fun successResetsAndReportsWrongAttempts() {
        repeat(5) { failOnce() }
        assertFalse(guard.tryBeginCheck())   // пропущена
        assertFalse(guard.tryBeginCheck())   // пропущена
        clock += 30_000
        assertTrue(guard.tryBeginCheck())
        assertEquals(7, guard.onSuccess())
        assertEquals(0, store.failures)
        assertTrue(guard.tryBeginCheck())
        assertEquals(0, guard.onSuccess())
    }

    @Test fun clockMovedBackRestartsThePause() {
        repeat(5) { failOnce() }
        clock -= 3_600_000   // часы перевели на час назад
        assertFalse(guard.tryBeginCheck())
        clock += 30_000
        assertTrue(guard.tryBeginCheck())
    }
}
