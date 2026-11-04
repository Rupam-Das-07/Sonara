import java.net.URI

// Default works for both a USB phone and an emulator with adb reverse :3002.
// Override per developer/machine without editing shared source.
val debugBackendUrl = (providers.gradleProperty("SONARA_DEBUG_BACKEND_URL").orNull
    ?: System.getenv("SONARA_DEBUG_BACKEND_URL")
    ?: "http://192.168.0.192:3002").trim().trimEnd('/')
val debugBackendUri = runCatching { URI(debugBackendUrl) }.getOrNull()
val hasValidDebugBackendUrl = debugBackendUri != null
    && debugBackendUri.scheme in setOf("http", "https")
    && !debugBackendUri.host.isNullOrBlank()
    && debugBackendUri.rawUserInfo == null
    && debugBackendUri.rawQuery == null
    && debugBackendUri.rawFragment == null
    && debugBackendUri.rawPath.isNullOrEmpty()
    && (debugBackendUri.port == -1 || debugBackendUri.port in 1..65535)
val verifyPortraits = providers.gradleProperty("SONARA_VERIFY_PORTRAITS").orNull == "true"
val failPortraits = providers.gradleProperty("SONARA_FAIL_PORTRAITS").orNull == "true"

val releaseBackendUrl = (providers.gradleProperty("SONARA_RELEASE_BACKEND_URL").orNull
    ?: System.getenv("SONARA_RELEASE_BACKEND_URL")
    ?: "").trim()
val releaseBackendUri: URI? = runCatching { URI.create(releaseBackendUrl) }.getOrNull()
val hasValidReleaseBackendUrl = releaseBackendUrl.isNotEmpty()
    && releaseBackendUri != null
    && releaseBackendUri.scheme.equals("https", ignoreCase = true)
    && !releaseBackendUri.host.isNullOrBlank()
    && releaseBackendUri.userInfo == null

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.sonara"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.sonara.music"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Release is always inert, regardless of debug verification properties.
        buildConfigField("boolean", "VERIFY_PORTRAITS", "false")
        buildConfigField("boolean", "FAIL_PORTRAITS", "false")
    }

    buildTypes {
        debug {
            // USB: adb -s <serial> reverse tcp:3002 tcp:3002 (reapply after disconnect).
            buildConfigField("String", "BASE_URL", "\"${if (hasValidDebugBackendUrl) debugBackendUrl else ""}\"")
            buildConfigField("boolean", "VERIFY_PORTRAITS", verifyPortraits.toString())
            buildConfigField("boolean", "FAIL_PORTRAITS", (verifyPortraits && failPortraits).toString())
        }
        release {
            optimization {
                enable = false
            }
            // Release builds strictly require HTTPS. The production endpoint is supplied via
            // Gradle project property (-PSONARA_RELEASE_BACKEND_URL=https://...) or environment variable.
            buildConfigField("String", "BASE_URL", "\"$releaseBackendUrl\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
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

// Do not make local debug tasks depend on release-only configuration. The check runs once
// the requested task graph is known, immediately before any release task executes.
gradle.taskGraph.whenReady {
    if (allTasks.any { it.path.contains("Debug", ignoreCase = true) } && !hasValidDebugBackendUrl) {
        throw GradleException("SONARA_DEBUG_BACKEND_URL must be an HTTP(S) origin without credentials, path, query or fragment.")
    }
    val releaseTaskRequested = allTasks.any { it.path.contains("Release", ignoreCase = true) }
    if (releaseTaskRequested && !hasValidReleaseBackendUrl) {
        throw GradleException(
            "SONARA_RELEASE_BACKEND_URL must be a valid HTTPS URL with a hostname. " +
                "Set it with -PSONARA_RELEASE_BACKEND_URL=https://<host> or the " +
                "SONARA_RELEASE_BACKEND_URL environment variable."
        )
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
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.exoplayer.hls)
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
    implementation(libs.androidx.webkit)
    implementation(libs.newpipe.extractor)
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
