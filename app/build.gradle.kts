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
        versionCode = 2
        versionName = "1.1"
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
