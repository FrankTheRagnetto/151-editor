import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional signing key, kept out of git: put .signing/keystore.properties (storeFile, storePassword,
// keyAlias, keyPassword) next to your keystore to sign debug and release with the same key.
// Without it, debug uses the standard Android debug key and release is built unsigned.
val signingFile = rootProject.file(".signing/keystore.properties")
val signingProps: Properties? = if (signingFile.exists()) Properties().apply { signingFile.inputStream().use { load(it) } } else null

val media3 = "1.11.1"

android {
    namespace = "com.the151universe.art"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.the151universe.art"
        minSdk = 26
        targetSdk = 36
        versionCode = 62
        versionName = "0.46.1"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // l'MP3: LAME compilato qui, dentro l'app (Android non ha un encoder MP3)
    ndkVersion = "27.2.12479018"
    externalNativeBuild { ndkBuild { path = file("src/main/cpp/Android.mk") } }

    signingConfigs {
        if (signingProps != null) create("art") {
            storeFile = rootProject.file(".signing/" + signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            if (signingProps != null) signingConfig = signingConfigs.getByName("art")
        }
        release {
            if (signingProps != null) signingConfig = signingConfigs.getByName("art")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += listOf("META-INF/*.version", "DebugProbesKt.bin")
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.google.android.material:material:1.13.0")
    // il motore video di Google: anteprima (ExoPlayer) ed esportazione (Transformer)
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    testImplementation("junit:junit:4.13.2")
}
