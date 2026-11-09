import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Google Places API key for the "nearby places" debug list. Put
//   PLACES_API_KEY=your-key
// in local.properties (never committed). It can also be pasted in Settings → Debug panel.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val placesApiKey: String = localProps.getProperty("PLACES_API_KEY") ?: System.getenv("PLACES_API_KEY") ?: ""
// Free Ola Maps key (100,000 calls/month), also only in local.properties: OLA_MAPS_API_KEY=...
val olaMapsApiKey: String = localProps.getProperty("OLA_MAPS_API_KEY") ?: System.getenv("OLA_MAPS_API_KEY") ?: ""
// Free TomTom key (2,500 requests/month, no card), also only in local.properties: TOMTOM_API_KEY=...
val tomTomApiKey: String = localProps.getProperty("TOMTOM_API_KEY") ?: System.getenv("TOMTOM_API_KEY") ?: ""

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
        buildConfigField("String", "PLACES_API_KEY", "\"${placesApiKey}\"")
        buildConfigField("String", "OLA_MAPS_API_KEY", "\"${olaMapsApiKey}\"")
        buildConfigField("String", "TOMTOM_API_KEY", "\"${tomTomApiKey}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
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
