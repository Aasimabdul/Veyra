# ============================================================
# Veyra ProGuard / R8 Security Rules
# ============================================================
# These rules protect user data by:
#   1. Stripping debug information from release builds
#   2. Obfuscating class/method names so APK reverse-engineering is harder
#   3. Removing logging calls that could leak sensitive data
#   4. Keeping only the classes the app actually needs at runtime
#
# Applied to release builds. Debug builds are unminified.
# ============================================================

# ---- General optimisation flags --------------------------------
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''            # Move all classes to root package (harder to reverse)
-dontskipnonpubliclibraryclasses

# ---- Strip debug logs (never leak data to logcat in production) -
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    # Keep w/e so runtime errors are still visible in crash reports
}

# ---- Kotlin -----------------------------------------------------
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers class kotlin.jvm.internal.DefaultConstructorMarker { <init>(...); }
-dontwarn kotlin.**

# ---- AndroidX / Jetpack ----------------------------------------
-keep class androidx.** { *; }
-dontwarn androidx.**

# ---- GeckoView --------------------------------------------------
# GeckoView uses native JNI reflection extensively; keep all public GeckoView types.
-keep class org.mozilla.geckoview.** { *; }
-dontwarn org.mozilla.geckoview.**

# ---- Media3 / ExoPlayer ----------------------------------------
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ---- WireGuard --------------------------------------------------
-keep class com.wireguard.** { *; }
-dontwarn com.wireguard.**

# ---- Veyra app classes (keep public API surface) ----------------
# Security-critical classes: never rename so Keystore key aliases stay stable.
-keep class com.veyra.browser.VaultKeys { *; }
-keep class com.veyra.browser.VpnManager { *; }
-keep class com.veyra.browser.SecurityPolicy { *; }

# Keep enum names (used in SharedPreferences string keys)
-keepclassmembers enum com.veyra.browser.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---- JSON / reflection ------------------------------------------
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# ---- Crash reporters / stack traces ----------------------------
# Keep line numbers in stack traces for crash reports without exposing source names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- FileProvider -----------------------------------------------
-keep class androidx.core.content.FileProvider { *; }

# ---- Remove BuildConfig fields that could leak paths ------------
-assumenosideeffects class com.veyra.browser.BuildConfig {
    public static final boolean DEBUG;
}
