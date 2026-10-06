buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9's bundled KGP can't read metadata from pebblekit2 (newer Kotlin); bump it.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
