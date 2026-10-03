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
        AppController.isForeground = true
    }

    override fun onStop() {
        super.onStop()
        AppController.isForeground = false
        // Ушли из приложения (домой, в другое приложение, выключили экран) —
        // тайник запирается сразу. Поворот экрана тайник не запирает.
        if (!isChangingConfigurations) {
            AppController.lock()
        }
    }
}
