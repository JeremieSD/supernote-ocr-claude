plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "dev.snocr.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.snocr.claudenotes"
        minSdk = 26
        // targetSdk 29 keeps legacy external storage on Android 11 (the
        // Manta), so the app can read /storage/emulated/0/Note directly with
        // the plain READ_EXTERNAL_STORAGE permission.
        targetSdk = 29
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("sideload") {
            // Committed keystore so sideloaded updates keep a stable
            // signature. It protects nothing secret; see README.
            storeFile = rootProject.file("signing/sideload.jks")
            storePassword = "sideload"
            keyAlias = "sideload"
            keyPassword = "sideload"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Sideload app built in CI; don't fail the build on lint findings.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":notekit"))
    implementation(project(":claudekit"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
