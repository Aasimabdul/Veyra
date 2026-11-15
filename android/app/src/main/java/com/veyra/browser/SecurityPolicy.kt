package com.veyra.browser

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * Security hardening checks for Veyra.
 *
 * These checks run at startup and are used to warn the user about conditions
 * that could compromise their privacy or data security. No data is sent anywhere;
 * all checks are purely local.
 *
 * Checks performed:
 *  1. Root/jailbreak detection   – rooted devices bypass the Android sandbox.
 *  2. Developer options / ADB    – USB debugging allows process inspection.
 *  3. Debuggable build guard     – release builds should never be debuggable.
 *  4. Installer source check     – sideloaded APKs may be tampered.
 *  5. Screen lock presence       – no lock = no Keystore-backed encryption possible.
 */
object SecurityPolicy {

    data class Warning(val code: String, val title: String, val detail: String)

    /**
     * Returns all security warnings that apply to this device/environment.
     * Returns an empty list when everything is clean.
     */
    fun audit(context: Context): List<Warning> {
        val warnings = mutableListOf<Warning>()

        // 1. Root detection
        if (isRooted()) {
            warnings += Warning(
                code = "ROOTED",
                title = "Device is rooted",
                detail = "Root access removes the Android security sandbox. Other apps may be " +
                    "able to read Veyra's private data, keys, and browsing history. " +
                    "Use Veyra on a non-rooted device for full privacy protection."
            )
        }

        // 2. ADB / developer options
        if (adbEnabled(context)) {
            warnings += Warning(
                code = "ADB_ON",
                title = "USB debugging is on",
                detail = "Android Debug Bridge (adb) allows a connected computer to inspect " +
                    "running processes. Disable USB debugging in Developer options when " +
                    "you are not actively developing."
            )
        }

        // 3. Debuggable release build
        val isDebug = context.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (isDebug && !BuildConfig.DEBUG) {
            warnings += Warning(
                code = "DEBUGGABLE_RELEASE",
                title = "Release build is debuggable",
                detail = "This APK was signed as a release build but still has the debuggable " +
                    "flag set. This is a packaging error that exposes the app to debugging attacks."
            )
        }

        // 4. Installer source (sideloaded APK)
        if (isSideloaded(context)) {
            warnings += Warning(
                code = "SIDELOADED",
                title = "App installed from unknown source",
                detail = "Veyra was not installed from the Play Store or a known package manager. " +
                    "If you downloaded this APK from an unofficial source, it may have been " +
                    "tampered with. Use official releases from the Veyra GitHub releases page."
            )
        }

        // 5. Screen lock absent
        if (!hasScreenLock(context)) {
            warnings += Warning(
                code = "NO_SCREEN_LOCK",
                title = "No screen lock set",
                detail = "Without a PIN, pattern or biometric lock, Android cannot back the " +
                    "Keystore keys used for Veyra's encrypted Private Space and VPN config. " +
                    "Set a screen lock in Android Settings → Security."
            )
        }

        return warnings
    }

    // ---- individual checks -------------------------------------------------------

    private fun isRooted(): Boolean {
        // Check for well-known su binary locations and Magisk mount points.
        val paths = arrayOf(
            "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/data/local/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su",
            "/data/adb/magisk",   // Magisk root
            "/sbin/.magisk"       // Magisk hide mount
        )
        if (paths.any { File(it).exists() }) return true

        // Try executing "which su" – will throw on stock devices (no su in PATH).
        return try {
            Runtime.getRuntime().exec(arrayOf("which", "su")).waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun adbEnabled(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.ADB_ENABLED, 0
            ) != 0
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ADB_ENABLED, 0
            ) != 0
        }
    }

    private fun isSideloaded(context: Context): Boolean {
        val trustedInstallers = setOf(
            "com.android.vending",         // Google Play Store
            "com.amazon.venezia",          // Amazon Appstore
            "org.fdroid.fdroid",           // F-Droid
            "com.aurora.store",            // Aurora Store (privacy Play client)
            "com.github.android",          // GitHub Releases via browser
            null                           // Pre-installed / ADB install (CI)
        )
        val installer = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager
                    .getInstallSourceInfo(context.packageName)
                    .installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        return installer !in trustedInstallers
    }

    private fun hasScreenLock(context: Context): Boolean {
        return try {
            val km = context.getSystemService(android.app.KeyguardManager::class.java)
            km?.isDeviceSecure == true
        } catch (_: Exception) {
            true  // Assume secure if we cannot check; don't false-alarm on unusual ROMs.
        }
    }
}
