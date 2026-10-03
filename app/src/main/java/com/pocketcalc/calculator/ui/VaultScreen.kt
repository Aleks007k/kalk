package com.pocketcalc.calculator.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketcalc.calculator.R
import com.pocketcalc.calculator.ui.theme.calcPalette

/**
 * Экран открытого тайника. Одинаков для настоящего и фальшивого тайника.
 * Кнопка «Назад» и «Закрыть» запирают тайник.
 * Добавление и просмотр файлов появятся на следующих этапах.
 */
@Composable
fun VaultScreen(onLock: () -> Unit) {
    SecureWindow()
    BackHandler(onBack = onLock)
    val palette = calcPalette(isSystemInDarkTheme())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.vault_title),
                color = palette.displayPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onLock) {
                Text(text = stringResource(R.string.vault_lock), color = palette.opBg, fontSize = 17.sp)
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.vault_empty_title),
                color = palette.displayPrimary,
                fontSize = 20.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.vault_empty_text),
                color = palette.displaySecondary,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
