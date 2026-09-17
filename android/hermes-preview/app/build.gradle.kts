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
        versionCode = 2
        versionName = "0.2-preview"
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

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
