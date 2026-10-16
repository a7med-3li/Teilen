import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties


val keystorePropertiesFile: File? = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile?.exists() == true) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.teilen.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProperties.getProperty("storeFile"))
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
    }
    defaultConfig {
        applicationId = "com.teilen.app"
        // 26 = notification channels; the clipboard flow is built on them
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.0"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.goterl:lazysodium-android:5.1.0@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    // official libwebrtc Android build (the org.webrtc:google-webrtc artifact died with JCenter):
    // PeerConnection + DataChannel for the local fast-path
    implementation("io.github.webrtc-sdk:android:125.6422.07")
    // the signaling socket only; the data channel carries the actual bytes
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
