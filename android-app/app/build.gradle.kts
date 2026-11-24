import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
        .asText.orNull?.let { load(it.reader()) }
}
val yandexClientId = providers.environmentVariable("YANDEX_CLIENT_ID").orNull
    ?.trim()?.takeIf { it.isNotEmpty() }
    ?: localProperties.getProperty("yandex.clientId").orEmpty().trim()
require(yandexClientId.isEmpty() || yandexClientId.matches(Regex("[A-Za-z0-9_-]+"))) {
    "Use the Client ID from Yandex OAuth in YANDEX_CLIENT_ID or yandex.clientId"
}

android {
    namespace = "ru.ix1ax.kotel"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.ix1ax.kotel"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        resValue("string", "yandex_oauth_client_id", yandexClientId)
    }

    // Один и тот же ключ подписи при каждой сборке — чтобы новые версии
    // ставились поверх старой без удаления (ключ лежит в репозитории, только для личного использования).
    signingConfigs {
        create("kotel") {
            storeFile = file("kotel.keystore")
            storePassword = "kotel-widget"
            keyAlias = "kotel"
            keyPassword = "kotel-widget"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("kotel")
        }
        debug {
            signingConfig = signingConfigs.getByName("kotel")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}
