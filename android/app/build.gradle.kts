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
    }

    // GeckoView ships a large native engine (libxul.so) per CPU architecture. Build one APK
    // per ABI instead of one huge multi-ABI APK:
    //   app-arm64-v8a-debug.apk    -> 64-bit ARM phones
    //   app-armeabi-v7a-debug.apk  -> 32-bit ARM phones
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        jniLibs {
            // Keep native libs compressed inside the APK so the file itself is much smaller
            // (they are extracted at install time).
            useLegacyPackaging = true
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
