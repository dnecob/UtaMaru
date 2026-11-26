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
        versionCode = 46
        versionName = "0.8.11.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // CMake downloads missing Signalsmith headers during initial Gradle Sync.
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
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
