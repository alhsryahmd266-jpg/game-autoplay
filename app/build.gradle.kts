import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val vn = (project.findProperty("vn") as String?) ?: "2.0.0"
val vc = ((project.findProperty("vc") as String?) ?: "200").toInt()
val ksFile = File(projectDir, "autoplay.p12")

android {
    namespace = "com.omega.autoplay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.omega.autoplay.pro"
        minSdk = 26
        targetSdk = 33
        versionCode = vc
        versionName = vn
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (ksFile.exists()) {
            create("stable") {
                storeFile = ksFile
                storeType = "pkcs12"
                storePassword = "autoplay-ks"
                keyAlias = "autoplay"
                keyPassword = "autoplay-ks"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksFile.exists()) signingConfigs.getByName("stable") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources { excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES") }
    }
}

dependencies {
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.2")
    implementation("androidx.annotation:annotation:1.8.2")
}
