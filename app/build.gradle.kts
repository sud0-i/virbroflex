plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.vibro.trigger"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.vibro.trigger"
        minSdk = 24
        targetSdk = 34
        // Номер сборки GitHub Actions, чтобы каждая новая сборка была новее предыдущей
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.$build"
    }

    // Постоянный ключ, чтобы новые сборки ставились поверх старых без удаления
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
