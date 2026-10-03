package com.pocketcalc.calculator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pocketcalc.calculator.ui.AppController
import com.pocketcalc.calculator.ui.AppRoot
import com.pocketcalc.calculator.ui.theme.CalculatorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as CalcApp
        AppController.initOnce(app.repository)
        setContent {
            CalculatorTheme {
                AppRoot(app)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppController.onActivityStart()
    }

    override fun onStop() {
        super.onStop()
        // Ушли из приложения (домой, в другое приложение, выключили экран) —
        // тайник запирается сразу. Поворот экрана и системные окна, открытые
        // нашей кнопкой (выбор файлов), тайник сразу не запирают.
        AppController.onActivityStop(isChangingConfigurations)
    }
}
