plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "se.snc.camera"
    compileSdk = 36

    defaultConfig {
        applicationId = "se.snc.camera"
        // LineageOS 23.x bygger på Android 16 (API 36). minSdk 31 gör att appen även går på äldre enheter.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        // OnePlus 13R är arm64 – håller APK:n liten
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        // Valfri egen nyckel via miljövariabler (används av GitHub Actions om de är satta)
        create("release") {
            val ks = System.getenv("SNC_KEYSTORE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("SNC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SNC_KEY_ALIAS")
                keyPassword = System.getenv("SNC_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val ks = System.getenv("SNC_KEYSTORE")
            signingConfig = if (ks != null && file(ks).exists()) {
                signingConfigs.getByName("release")
            } else {
                // Ingen egen nyckel: signera med debug-nyckeln så att APK:n går att installera direkt
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    lint {
        // Lint-varningar ska inte stoppa bygget av APK:n
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val cameraX = "1.4.2"
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")

    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("androidx.camera:camera-video:$cameraX")
    implementation("androidx.camera:camera-extensions:$cameraX")

    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.exifinterface:exifinterface:1.4.1")

    // Textigenkänning – inbäddad modell, kräver INTE Google Play-tjänster (fungerar på LineageOS utan GApps)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Mjukvaruporträtt (bakgrundsoskärpa) när tillverkarens porträttläge saknas – också inbäddad modell
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
}
