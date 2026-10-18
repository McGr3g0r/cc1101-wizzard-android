/*
 * CC1101 Wizzard
 * Author: Grzegorz Rajtar
 * e-mail: mcgregor@blackmesaeast.com.pl
 * (c) 2026
 */

import java.io.ByteArrayOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** Run a git command, returning its trimmed stdout or "" on any failure. */
fun git(vararg args: String): String = try {
    val out = ByteArrayOutputStream()
    exec {
        commandLine(listOf("git") + args)
        standardOutput = out
        errorOutput = ByteArrayOutputStream()
        isIgnoreExitValue = true
    }
    out.toString().trim()
} catch (e: Exception) { "" }

// versionName comes from the latest git tag (e.g. "v2.3"); versionCode is the
// commit count (always increasing); the short commit hash is exposed separately.
val gitTag: String = git("describe", "--tags", "--abbrev=0").ifEmpty { "v2.2" }
val gitHash: String = git("rev-parse", "--short", "HEAD").ifEmpty { "nogit" }
val gitDirty: Boolean = git("status", "--porcelain").isNotEmpty()
val gitCount: Int = git("rev-list", "--count", "HEAD").toIntOrNull() ?: 1

android {
    namespace = "com.wizzard.cc1101"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wizzard.cc1101"
        minSdk = 29          // Android 10+
        targetSdk = 34
        versionCode = gitCount
        versionName = gitTag
        buildConfigField("String", "GIT_HASH", "\"$gitHash${if (gitDirty) "-dirty" else ""}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true   // exposes BuildConfig.VERSION_NAME / VERSION_CODE
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")

    // USB CDC/ACM + FTDI/CP210x/CH34x serial driver
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")

    // JSON for monitor-line parsing and saved-packet files
    implementation("org.json:json:20240303")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
