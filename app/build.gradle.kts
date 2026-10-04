import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Ключ подписи НЕ хранится в репозитории (см. комментарий в signingConfigs).
val ksProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun sign(name: String): String? = System.getenv(name) ?: ksProps.getProperty(name)
val ksFile: File? = sign("KEYSTORE_FILE")?.let { rootProject.file(it) }
    ?: file("obd-scanner.jks").takeIf { it.exists() }
val ksPassword: String? = sign("KEYSTORE_PASSWORD")

android {
    namespace = "com.kostyalke.obdscanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kostyalke.obdscanner"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // Ключ подписи НЕ хранится в репозитории.
    // CI: берётся из GitHub Secrets (KEYSTORE_BASE64 → app/obd-scanner.jks + пароли в env).
    // Локально: файл keystore.properties в корне проекта (см. README). Если ни того, ни другого
    // нет — обычная debug-подпись Android Studio.
    val hasSigning = ksFile != null && ksFile.exists() && ksPassword != null

    signingConfigs {
        if (hasSigning) {
            create("app") {
                storeFile = ksFile
                storePassword = ksPassword
                keyAlias = sign("KEY_ALIAS") ?: "obd"
                keyPassword = sign("KEY_PASSWORD") ?: ksPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasSigning) signingConfigs.getByName("app") else signingConfigs.getByName("debug")
        }
        debug {
            if (hasSigning) signingConfig = signingConfigs.getByName("app")
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}

// Скриншот-тесты подключаются только по флагу, чтобы не мешать обычной сборке и CI.
if (project.hasProperty("screenshots")) {
    apply(plugin = "app.cash.paparazzi")
    android.sourceSets.getByName("test").java.srcDir("src/screenshotTest/java")
}
