import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
}

kotlin {
    androidLibrary {
        namespace = "com.banalities.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    jvm()
    iosArm64()            // device
    iosSimulatorArm64()   // Apple-Silicon simulator (Compose MP no longer ships iosX64)
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }
}
