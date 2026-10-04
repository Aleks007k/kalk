plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.pocketcalc.calculator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pocketcalc.calculator"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.6.0"
    }

    // Подпись релиза.
    // Постоянный ключ лежит в signing/release.p12 и зашифрован паролем, который
    // знает только владелец. Пароль передаётся сборке через секрет GitHub
    // SIGNING_PASSWORD. Без пароля (локальная сборка, чужой PR) релиз
    // подписывается отладочным ключом — такие сборки не публикуются
    // (CI проверяет подпись перед выпуском релиза).
    val keystoreFile = rootProject.file("signing/release.p12")
    val signingPassword: String? = System.getenv("SIGNING_PASSWORD")
    val hasRealSigning = keystoreFile.exists() && !signingPassword.isNullOrBlank()

    signingConfigs {
        if (hasRealSigning) {
            create("release") {
                storeFile = keystoreFile
                storePassword = signingPassword
                keyAlias = "calculator"
                keyPassword = signingPassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            // Сжатие кода пока выключено ради максимально надёжной первой сборки.
            // Включим на этапе 2, когда появится код тайника.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasRealSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Шифрование: Google Tink (потоковое AES-GCM) + Argon2 из Bouncy Castle.
    implementation(libs.tink)
    implementation(libs.bouncycastle)

    // Просмотр: видео (Media3 ExoPlayer) и увеличение фото пальцами (Telephoto).
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.telephoto.zoomable)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
