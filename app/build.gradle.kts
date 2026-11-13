plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.example.voicepitchlooper"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.example.voicepitchlooper"
        minSdk = 31              // Android 12+
        targetSdk = 36
        versionCode = 42
        versionName = "0.8.7.22-localization"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Native build is optional until SETUP_RUBBERBAND.bat downloads the official
    // GPL source. This lets the two existing audio engines build without an NDK.
    if (file("../third_party/rubberband-4.0.0/single/RubberBandSingle.cpp").exists()) {
        externalNativeBuild {
            cmake { path = file("src/main/cpp/CMakeLists.txt") }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}
