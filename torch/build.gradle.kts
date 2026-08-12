// AGP 9 has built-in Kotlin support and registers the `kotlin` extension itself, so
// org.jetbrains.kotlin.android must NOT be applied here — doing so fails with
// "Cannot add extension with name 'kotlin'".
plugins {
    id("com.android.application")
}

android {
    namespace = "com.shhh.torch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shhh.torch"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
