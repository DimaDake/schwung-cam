plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.dimadake.schwungcam"
    compileSdk = 34
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "io.github.dimadake.schwungcam"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        // 16 KB pages (Android 15+ devices): NDK 27 still links at 4 KB by default
        externalNativeBuild { cmake { arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON" } }
    }

    // the Link SDK (GPL-2.0-or-later) for Link Audio stems
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // tools/build-probe.sh builds the native true-peak detector for this Mac; NativePeaksTest runs it when there
        unitTests.all { t ->
            val lib = rootProject.file("build-probe/libschwungcam_dsp.dylib")
            if (lib.exists()) t.systemProperty("schwungcam.dsplib", lib.absolutePath)
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
