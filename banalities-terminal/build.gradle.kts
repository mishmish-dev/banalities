plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeCompiler)
    application
}

dependencies {
    implementation(project(":banalities-core"))
    implementation(libs.mosaic.runtime)
}

application {
    mainClass.set("com.banalities.terminal.MainKt")
}
