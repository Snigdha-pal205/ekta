plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Only apply Google Services once you've dropped in app/google-services.json
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.ekta.sos"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ekta.sos"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.17.0")

    implementation("com.google.android.gms:play-services-location:21.3.0")
    // Phone-to-phone mesh over Bluetooth / BLE / Wi-Fi Direct, no internet needed
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation(platform("com.google.firebase:firebase-bom:35.0.2"))
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-messaging")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
}
