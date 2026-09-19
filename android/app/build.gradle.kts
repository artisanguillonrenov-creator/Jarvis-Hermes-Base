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
//
// hermes-agent's own setup.py deliberately REFUSES to build a wheel/sdist
// outside a Nix build ("the wheel would ship without bundled assets --
// locales, skills, optional-mcps, web_dist, tui_dist, plugin manifests --
// since those are resolved at runtime via ... the source-checkout
// layout"). So this does NOT `pip install` hermes-agent itself -- it adds
// the source checkout as a Chaquopy Python source directory instead
// (matching the "source-checkout layout" the project's own runtime
// asset resolution expects), and uses pip only for hermes-agent's real
// third-party dependencies (openai, httpx, cryptography, ...), which have
// no such constraint.
val hermesSrcDir = providers.environmentVariable("HERMES_ANDROID_SRC_DIR")
    .getOrElse(rootProject.projectDir.parentFile.resolve("../hermes-src-staged").absolutePath)

chaquopy {
    defaultConfig {
        version = "3.12"
        pip {
            install("-r", "requirements.txt")
        }
    }
    sourceSets {
        getByName("main") {
            srcDir(hermesSrcDir)
        }
    }
}

dependencies {
}
