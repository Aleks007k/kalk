package com.pocketcalc.calculator.ui

import android.content.Context

/**
 * Настройки вида. Хранится только число колонок сетки: оно одно на весь
 * телефон (одинаково для настоящего и фальшивого тайника) и ничего не
 * говорит о спрятанных файлах.
 */
object UiPrefs {

    const val MIN_COLUMNS = 2
    const val MAX_COLUMNS = 6
    private const val DEFAULT_COLUMNS = 3

    private const val FILE = "ui"
    private const val KEY_COLUMNS = "grid_columns"

    fun gridColumns(context: Context): Int =
        prefs(context).getInt(KEY_COLUMNS, DEFAULT_COLUMNS).coerceIn(MIN_COLUMNS, MAX_COLUMNS)

    fun setGridColumns(context: Context, columns: Int) {
        prefs(context).edit().putInt(KEY_COLUMNS, columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
