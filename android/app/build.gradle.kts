import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.teilen.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.teilen.app"
        // 26 = notification channels; the clipboard flow is built on them
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// libsodium (bundles the native .so for every ABI) and its JNA bridge; JNA also ships as an aar
// on Android. Everything else is still the platform: EditText and HttpURLConnection — plus the
// libwebrtc stack and an OkHttp WebSocket for the direct LAN fast-path (Phase 4).
dependencies {
    implementation("com.goterl:lazysodium-android:5.1.0@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    // official libwebrtc Android build (the org.webrtc:google-webrtc artifact died with JCenter):
    // PeerConnection + DataChannel for the local fast-path
    implementation("io.github.webrtc-sdk:android:125.6422.07")
    // the signaling socket only; the data channel carries the actual bytes
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
