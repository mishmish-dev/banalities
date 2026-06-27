import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

kotlin {
    jvm()
    androidTarget()
    iosArm64()            // device
    iosSimulatorArm64()   // Apple-Silicon simulator (Compose MP no longer ships iosX64)
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }

    // sourceSets are wired by directory layout; commonMain has no extra deps yet.
}

android {
    namespace = "com.banalities.core"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}
