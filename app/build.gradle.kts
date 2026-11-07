plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.eokul.studio"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.eokul.studio"
        minSdk = 29
        targetSdk = 34
        // CI'da her çalıştırma numarası sürüm kodunu artırır; yerelde 1.
        val runNo = System.getenv("VERSION_CODE")?.toIntOrNull() ?: System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = runNo
        versionName = System.getenv("VERSION_NAME") ?: "1.0.$runNo"
        // Yalnızca modern telefon mimarisi: x86/armeabi-v7a yerel kütüphaneleri APK'yı şişirir.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // İmza bilgileri kaynak kodda DEĞİL, ortam değişkenlerinde (GitHub Secrets) tutulur.
    // Anahtar yoksa release sürümü Android'in debug anahtarıyla imzalanır (kurulur ama her derlemede imza değişir).
    val ksFile = System.getenv("KEYSTORE_FILE")
    val hasReleaseKey = ksFile != null && file(ksFile).exists() && System.getenv("KEYSTORE_PASSWORD") != null
    signingConfigs {
        if (hasReleaseKey) {
            create("eokulRelease") {
                storeFile = file(ksFile!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: "eokul"
                keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Debug: Android'in varsayılan debug anahtarı (özel anahtar kullanılmaz).
            isMinifyEnabled = false
        }
        release {
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("eokulRelease") else signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/*.version", "kotlin/**", "DebugProbesKt.bin")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // Uygulama içi kamera
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    // Yüz algılama: model uygulamanın içinde gelir, internet / Play Hizmetleri indirmesi gerekmez.
    implementation("com.google.mlkit:face-detection:16.1.7")
    // Kişi maskesi: saç tepesini doğru ölçmek için (model uygulamanın içinde gelir).
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    // Birim testleri (çekirdek mantık: liste ayrıştırma, Excel okuma, kırpma/JPEG hattı, kalite ölçümü)
    testImplementation("junit:junit:4.13.2")
}
