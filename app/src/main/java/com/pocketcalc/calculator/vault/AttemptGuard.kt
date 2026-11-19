package com.pocketcalc.calculator.vault

/**
 * Тихая защита от подбора PIN в калькуляторе.
 *
 * Первые [FREE_FAILURES] неверных попыток подряд проверяются сразу. Дальше
 * PIN проверяется не чаще раза в 30 секунд, после 10 неверных — раз в
 * 5 минут, после 20 — раз в 30 минут. Слишком ранняя попытка просто не
 * проверяется: снаружи это неотличимо от неверного PIN (калькулятор считает
 * как обычно), поэтому перебирающий не узнает, что его притормозили.
 * Верный вход обнуляет счётчики.
 *
 * Время — по часам телефона: кто переведёт их вперёд, обойдёт паузу, но для
 * этого надо знать о ней. Если часы перевели назад, пауза отсчитывается заново.
 */
class AttemptGuard(private val store: Store, private val now: () -> Long = System::currentTimeMillis) {

    /** Где хранятся счётчики (в приложении — настройки телефона, в тестах — память). */
    interface Store {
        /** Неверных проверок подряд. */
        var failures: Int

        /** Попыток, которые не проверялись из-за паузы. */
        var skipped: Int

        /** Когда была последняя проверка, мс. */
        var lastCheckAt: Long
    }

    /** Можно ли проверить PIN прямо сейчас. Если нет — попытка засчитывается как пропущенная. */
    fun tryBeginCheck(): Boolean {
        val wait = pauseAfter(store.failures)
        if (wait == 0L) return true
        val t = now()
        if (t < store.lastCheckAt) {
            // Часы перевели назад — считаем паузу с этого момента.
            store.lastCheckAt = t
        }
        if (t - store.lastCheckAt >= wait) return true
        store.skipped += 1
        return false
    }

    /** PIN проверили — не подошёл. */
    fun onFailure() {
        store.failures += 1
        store.lastCheckAt = now()
    }

    /**
     * Вход удался. Возвращает, сколько раз с прошлого входа набирали неверный
     * PIN (проверенные и пропущенные попытки), и обнуляет счётчики.
     */
    fun onSuccess(): Int {
        val wrong = store.failures + store.skipped
        store.failures = 0
        store.skipped = 0
        store.lastCheckAt = 0
        return wrong
    }

    companion object {
        const val FREE_FAILURES = 5

        /** Пауза перед следующей проверкой после [failures] неверных подряд. */
        fun pauseAfter(failures: Int): Long = when {
            failures < FREE_FAILURES -> 0L
            failures < 10 -> 30_000L
            failures < 20 -> 5 * 60_000L
            else -> 30 * 60_000L
        }
    }
}
