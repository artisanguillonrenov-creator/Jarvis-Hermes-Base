plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.hermes.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hermes.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // William's tablet (Galaxy Tab A11) is arm64 only — no universal build.
        ndk {
            abiFilters += "arm64-v8a"
        }
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
}

// Chaquopy embeds a real CPython build directly into the APK (replaces the
// abandoned Termux-style bootstrap from Phase 2 -- that approach fought
// coreutils' gnulib configure hanging repeatedly under this project's
// sandboxed Docker CI; Chaquopy sidesteps the whole class of problem by
// shipping a prebuilt Android CPython and installing pip packages against
// Android wheels via a mature, widely-used toolchain instead of
// recompiling a whole Unix userland from source).
//
// hermes-agent's own pyproject.toml caps at <3.14 because some Rust-backed
// transitives (pydantic-core) have no cp314 wheel yet -- 3.12 is a safe,
// well-supported middle ground.
chaquopy {
    defaultConfig {
        version = "3.12"
        pip {
            // Install hermes-agent itself from this checkout (the Android
            // project lives at <repo>/android/app, so the repo root --
            // which has pyproject.toml -- is two levels up). No extras
            // yet: this is the minimal set to get Chaquopy's pip/wheel
            // pipeline proven out end to end first. Extras (termux, cron,
            // mcp, ...) get added once this baseline installs and the
            // dashboard launches.
            install(rootProject.projectDir.parentFile.absolutePath)
        }
    }
}

dependencies {
}
