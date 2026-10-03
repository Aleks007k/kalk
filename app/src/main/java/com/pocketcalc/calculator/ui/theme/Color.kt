package com.pocketcalc.calculator.ui.theme

import androidx.compose.ui.graphics.Color

/** Набор цветов калькулятора под светлую или тёмную тему. */
data class CalcPalette(
    val background: Color,
    val displayPrimary: Color,
    val displaySecondary: Color,
    val digitBg: Color,
    val digitText: Color,
    val funcBg: Color,
    val funcText: Color,
    val opBg: Color,
    val opText: Color,
    val equalsBg: Color,
    val equalsText: Color,
    val error: Color,
)

private val DarkPalette = CalcPalette(
    background = Color(0xFF0E0E12),
    displayPrimary = Color(0xFFFFFFFF),
    displaySecondary = Color(0xFF8A8A94),
    digitBg = Color(0xFF1C1C22),
    digitText = Color(0xFFF2F2F5),
    funcBg = Color(0xFF2A2A33),
    funcText = Color(0xFFD6D6DE),
    opBg = Color(0xFF3B74FF),
    opText = Color(0xFFFFFFFF),
    equalsBg = Color(0xFFFF8A00),
    equalsText = Color(0xFFFFFFFF),
    error = Color(0xFFFF6B6B),
)

private val LightPalette = CalcPalette(
    background = Color(0xFFF7F7FA),
    displayPrimary = Color(0xFF15151A),
    displaySecondary = Color(0xFF8A8A94),
    digitBg = Color(0xFFFFFFFF),
    digitText = Color(0xFF15151A),
    funcBg = Color(0xFFE7E7EE),
    funcText = Color(0xFF3A3A44),
    opBg = Color(0xFF3B74FF),
    opText = Color(0xFFFFFFFF),
    equalsBg = Color(0xFFFF8A00),
    equalsText = Color(0xFFFFFFFF),
    error = Color(0xFFD23B3B),
)

fun calcPalette(dark: Boolean): CalcPalette = if (dark) DarkPalette else LightPalette
