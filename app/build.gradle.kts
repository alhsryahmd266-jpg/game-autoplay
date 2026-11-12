plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.omega.autoplay"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.omega.autoplay"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "tflite" }
}
dependencies {
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
}
