plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cortana.proofofarchitecture"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cortana.proofofarchitecture"
        // Samsung Galaxy Tab A11 ships arm64-v8a; that is the only ABI this spike targets.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.5-spike"
        ndk { abiFilters += "arm64-v8a" }
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
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    // Mandatory per the spike spec (§4.1): without this, jniLibs binaries stay compressed
    // inside the APK and lose the executable bit at install time — the runtime fails silently.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Kotlin coroutines drive every long-running runtime/RPC step off the main thread.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // OkHttp's WebSocket client speaks the newline-delimited JSON-RPC wire protocol
    // tui_gateway/ws.py implements. org.json ships with the Android platform (no extra dep).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
