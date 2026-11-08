// Yükseltme (Kasım 2026): AGP 9.5.0 yerleşik Kotlin kullanır; Kotlin sürümünü buradan sabitleriz.
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
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
