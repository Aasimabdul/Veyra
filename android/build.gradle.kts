// AGP 9 includes built-in Kotlin support, so org.jetbrains.kotlin.android is not applied.
// Dependencies (kotlin-stdlib 2.4.x) need a newer Kotlin compiler than AGP's default KGP 2.2.10,
// so the Kotlin Gradle plugin is upgraded as documented in the AGP 9.0 release notes.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.5.0" apply false
}
