plugins {
    id("com.android.application")
}

android {
    namespace = "com.veyra.browser"
    // GeckoView 159 requires compile SDK 37.2 (platforms;android-37.2)
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.veyra.browser"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // GeckoView ships native engine libs per CPU architecture. Keeping only 64-bit ARM
        // (virtually all current phones) cuts the APK from ~530 MB to well under 200 MB.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.mozilla.geckoview:geckoview:159.0.20261022150525")
}
