import java.util.Properties

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
        versionCode = 1
        versionName = "1.0.0"
    }

    // Подпись релиза.
    // Ключ НЕ хранится в репозитории. На GitHub его передают через секреты:
    //   SIGNING_KEYSTORE_PATH — путь к файлу ключа, который CI создаёт из секрета;
    //   SIGNING_PASSWORD      — пароль ключа;
    //   SIGNING_KEY_ALIAS     — имя ключа (по умолчанию "calculator").
    // Если секретов нет (локальная сборка или пока ключ не заведён) — релиз
    // подписывается отладочным ключом, чтобы сборка всегда проходила.
    val keystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")
    val signingPassword: String? = System.getenv("SIGNING_PASSWORD")
    val keyAliasEnv: String = System.getenv("SIGNING_KEY_ALIAS") ?: "calculator"
    val keystoreFile = keystorePath?.let { file(it) }
    val hasRealSigning = keystoreFile != null && keystoreFile.exists() && !signingPassword.isNullOrBlank()

    signingConfigs {
        if (hasRealSigning) {
            create("release") {
                storeFile = keystoreFile
                storePassword = signingPassword
                keyAlias = keyAliasEnv
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
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
