package com.pocketcalc.calculator

import android.app.Application
import com.pocketcalc.calculator.crypto.AndroidKeystoreGate
import com.pocketcalc.calculator.crypto.KeystoreGate
import com.pocketcalc.calculator.vault.VaultRepository
import java.io.File

/** Приложение: держит хранилище тайников и ворота защищённого чипа. */
class CalcApp : Application() {

    lateinit var repository: VaultRepository
        private set

    val gate: KeystoreGate = AndroidKeystoreGate()

    override fun onCreate() {
        super.onCreate()
        // Внутренняя папка приложения: другие приложения и галерея её не видят.
        repository = VaultRepository(File(filesDir, "vault"))
        repository.cleanupTmp()
    }
}
