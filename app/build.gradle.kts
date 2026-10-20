import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Same keystore as tools/build-offline.sh, so either build installs over the other.
// Decrypt it once with: gpg -d keystore/scrobot-release.jks.gpg > keystore/scrobot-release.jks
// and put the password in keystore/keystore.properties (both are git-ignored).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.mahmoud.scrobot"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mahmoud.scrobot"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "3.0"
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file("keystore/" + keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// No dependencies: only the Android framework and the Kotlin standard library.
