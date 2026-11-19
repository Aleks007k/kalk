package com.pocketcalc.calculator

import android.app.Application
import com.pocketcalc.calculator.crypto.AndroidKeystoreGate
import com.pocketcalc.calculator.crypto.KeystoreGate
import com.pocketcalc.calculator.vault.AttemptGuard
import com.pocketcalc.calculator.vault.VaultRepository
import com.pocketcalc.calculator.viewer.VaultPdf
import java.io.File

/** Приложение: держит хранилище тайников и ворота защищённого чипа. */
class CalcApp : Application() {

    lateinit var repository: VaultRepository
        private set

    val gate: KeystoreGate = AndroidKeystoreGate()

    /**
     * Тихая пауза после неверных PIN. Счётчики лежат в обычных настройках
     * приложения: это только числа попыток, без PIN и без сведений о файлах.
     */
    val attemptGuard: AttemptGuard by lazy {
        val prefs = getSharedPreferences("calc", MODE_PRIVATE)
        AttemptGuard(object : AttemptGuard.Store {
            override var failures: Int
                get() = prefs.getInt("f", 0)
                set(value) { prefs.edit().putInt("f", value).apply() }
            override var skipped: Int
                get() = prefs.getInt("s", 0)
                set(value) { prefs.edit().putInt("s", value).apply() }
            override var lastCheckAt: Long
                get() = prefs.getLong("t", 0L)
                set(value) { prefs.edit().putLong("t", value).apply() }
        })
    }

    /** Запасная папка для показа PDF на Android 8–10 (файлы стираются сразу после открытия). */
    val docTmpDir: File
        get() = File(noBackupFilesDir, "doc-tmp")

    override fun onCreate() {
        super.onCreate()
        // Внутренняя папка приложения: другие приложения и галерея её не видят.
        repository = VaultRepository(File(filesDir, "vault"))
        repository.cleanupTmp()
        VaultPdf.cleanup(docTmpDir)
    }
}
