plugins {
    id("com.android.application")
}

android {
    namespace = "com.shhh"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shhh"
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
