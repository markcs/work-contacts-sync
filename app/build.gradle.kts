import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Computed once at configuration time; stamped into the APK so you can tell
// at a glance on-device whether a fresh build is actually running.
val buildTime: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())

// Minutes since 2026-01-01, used as versionCode so each rebuild is strictly
// newer and `adb install -r` always performs an upgrade.
val buildVersionCode: Int =
    ((Date().time - 1767225600000L) / 60000L).toInt()

android {
    namespace = "com.markcs.workcontactssync"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.markcs.workcontactssync"
        minSdk = 30
        targetSdk = 34
        // Derive a monotonically increasing versionCode from the build time
        // (minutes since 2026-01-01). This guarantees every rebuild is a
        // strictly newer version, so `adb install -r` always upgrades the
        // installed app instead of silently keeping the old one.
        versionCode = buildVersionCode
        versionName = "1.0-$buildTime"

        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Release signing. Secrets live in keystore.properties (gitignored) or env
    // vars; falls back to no signing config if neither is present (so a clean
    // checkout without the keystore still configures/builds a debug APK).
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties()
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
    }

    signingConfigs {
        create("release") {
            val kFile = keystoreProperties["KEYSTORE_FILE"] as String? ?: System.getenv("KEYSTORE_FILE")
            val kPass = keystoreProperties["KEYSTORE_PASSWORD"] as String? ?: System.getenv("KEYSTORE_PASSWORD")
            val kAlias = keystoreProperties["KEY_ALIAS"] as String? ?: System.getenv("KEY_ALIAS")
            val kKeyPass = keystoreProperties["KEY_PASSWORD"] as String? ?: System.getenv("KEY_PASSWORD")

            if (kFile != null && kPass != null && kAlias != null && kKeyPass != null) {
                storeFile = file(kFile)
                storePassword = kPass
                keyAlias = kAlias
                keyPassword = kKeyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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

dependencies {
    // Classic Views + appcompat/material for the UI (no Compose, to avoid the
    // Compose-compiler/Kotlin version lockstep and keep the build robust).
    // Persistence is a hand-written SQLiteOpenHelper (no Room/KSP annotation
    // processor), implementing the exact SyncedContact schema from DESIGN.md.
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
}
