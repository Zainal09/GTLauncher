plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Versi otomatis: nomor build GitHub selalu naik, jadi APK baru selalu bisa menimpa yang lama
val appVersion = (project.findProperty("APP_VERSION") as String?) ?: "1.0"
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0

android {
    namespace = "com.gtlauncher.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gtlauncher.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1000 + runNumber
        versionName = "$appVersion.$runNumber"
    }

    // Kunci tanda tangan tetap, supaya APK baru selalu bisa menimpa yang lama
    // (hanya untuk pemakaian pribadi, bukan untuk Play Store)
    signingConfigs {
        create("personal") {
            storeFile = file("launcher.keystore")
            storePassword = "android"
            keyAlias = "launcher"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // Release: kode dioptimasi R8 dan tanpa pemeriksaan debug -> jauh lebih mulus dari debug
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    lint {
        // Jangan gagalkan build karena peringatan gaya kode
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
}
