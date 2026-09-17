plugins {
    id("com.android.application")
}

android {
    namespace = "com.nousresearch.hermespreview"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nousresearch.hermespreview"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-preview"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
