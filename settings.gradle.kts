rootProject.name = "banalities"

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

include(":banalities-core")
include(":banalities-ui")
include(":banalities-server")
include(":banalities-terminal")
include(":banalities-web")
include(":banalities-android")
// banalities-ios is an Xcode project, not a Gradle module — see banalities-ios/README.md
