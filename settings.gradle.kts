pluginManagement {
    repositories {
        // Filtered so only Android/Google artifacts hit the Google repo; the
        // pure-JVM modules resolve everything from Maven Central / the plugin
        // portal and stay buildable without Google connectivity.
        maven("https://maven.google.com") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.7.3"
        kotlin("android") version "2.0.21"
        kotlin("jvm") version "2.0.21"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.google.com") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "supernote-ocr-claude"

include(":notekit")
include(":claudekit")

// The Android app module needs the Android SDK (and reachable Google
// repositories). Skip it when no SDK is present so the pure-JVM modules
// remain buildable anywhere.
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
if (hasAndroidSdk) {
    include(":app")
} else {
    logger.lifecycle("Android SDK not found - building JVM modules only (:notekit, :claudekit)")
}
