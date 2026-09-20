plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.sonara"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.sonara"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Development backend endpoint (physical device LAN or emulator loopback)
            buildConfigField("String", "BASE_URL", "\"http://192.168.0.4:3002\"")
        }
        release {
            optimization {
                enable = false
            }
            // Release builds strictly require HTTPS. The production endpoint is supplied via
            // Gradle project property (-PSONARA_RELEASE_BACKEND_URL=https://...) or environment variable.
            // If none is supplied, it defaults to "" to strictly prevent baking in any fictional domain or dev IP.
            val releaseBackendUrl = (providers.gradleProperty("SONARA_RELEASE_BACKEND_URL").orNull
                ?: System.getenv("SONARA_RELEASE_BACKEND_URL")
                ?: "").trim()

            if (releaseBackendUrl.isNotEmpty() && !releaseBackendUrl.startsWith("https://")) {
                throw GradleException("SONARA_RELEASE_BACKEND_URL must begin with https://. Found: $releaseBackendUrl")
            }

            buildConfigField("String", "BASE_URL", "\"$releaseBackendUrl\"")
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
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.coil.compose)

    // Media3 Playback Subsystem (Phase 4B-1 / Audit 01)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.kotlinx.coroutines.guava)

    // Room Persistence (Phase 4B-3 / Phase 2)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore Preferences (Phase 4B-3 / Phase 2)
    implementation(libs.androidx.datastore.preferences)

    // Navigation Compose (Phase 4B-2 / Phase 2)
    implementation(libs.androidx.navigation.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}