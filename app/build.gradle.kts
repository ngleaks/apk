plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

val releaseStore = keystoreProps.getProperty("storeFile")

android {
    namespace = "ph.pbb.live"
    compileSdk = 35

    defaultConfig {
        applicationId = "ph.pbb.live"
        minSdk = 24
        targetSdk = 35
        versionCode = 200
        versionName = "2.0.0"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty() && releaseStore != null) {
            create("release") {
                storeFile = rootProject.file(releaseStore)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // No minification: the app is small (framework-only) and every screen is
            // hand-written; shrinking buys nothing and complicates debugging on-device.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // Zero third-party dependencies by design (lightweight, fast installs on TV boxes).
    // For DRM/ClearKey DASH, add AndroidX Media3 and swap the LivePlayer implementation:
    //   implementation("androidx.media3:media3-exoplayer:1.5.1")
    //   implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    //   implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
}
