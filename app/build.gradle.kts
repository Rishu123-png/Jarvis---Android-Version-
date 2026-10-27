plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Signing.
 *
 * CI machines generate a *fresh* debug keystore every build, which means two debug APKs built
 * on different machines have different signatures — and Android refuses to update one with the
 * other ("package conflicts with an existing package"). To avoid that, a single release
 * keystore is used for every build type when one is available.
 *
 * The keystore is looked up from the environment, so it never lives in the repository:
 *   Codemagic  -> CM_KEYSTORE_PATH / CM_KEYSTORE_PASSWORD / CM_KEY_ALIAS / CM_KEY_PASSWORD
 *   Local/other-> JARVIS_KEYSTORE_PATH / JARVIS_KEYSTORE_PASSWORD / JARVIS_KEY_ALIAS / JARVIS_KEY_PASSWORD
 *
 * With no keystore configured the build still works: everything falls back to debug signing.
 */
val releaseKeystorePath: String? = (
    System.getenv("CM_KEYSTORE_PATH")
        ?: System.getenv("JARVIS_KEYSTORE_PATH")
    )?.takeIf { it.isNotBlank() && file(it).exists() }

val hasReleaseKeystore = releaseKeystorePath != null

android {
    namespace = "com.jarvis.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.assistant"
        minSdk = 29
        targetSdk = 34
        versionCode = 18
        versionName = "1.0.0"

        vectorDrawables { useSupportLibrary = true }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = System.getenv("CM_KEYSTORE_PASSWORD")
                    ?: System.getenv("JARVIS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CM_KEY_ALIAS")
                    ?: System.getenv("JARVIS_KEY_ALIAS")
                keyPassword = System.getenv("CM_KEY_PASSWORD")
                    ?: System.getenv("JARVIS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // No applicationIdSuffix on purpose: exactly one JARVIS package on the phone, so a
            // new build always updates the installed one instead of creating a second icon.
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (hasReleaseKeystore) "release" else "debug")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Degrades to debug signing rather than failing when no keystore is present.
            signingConfig = signingConfigs.getByName(if (hasReleaseKeystore) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // The Shizuku user service is described by an AIDL interface.
        aidl = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    /*
     * Shizuku: optional adb-level shell access, granted by the operator, no root involved.
     * Both artifacts are inert when Shizuku is not installed — nothing binds, nothing blocks.
     */
    /*
     * CameraX: powers "take a photo" as a deterministic action. The system camera intent would need
     * the operator to press a shutter; this does not, so a spoken chain (capture -> show newest)
     * actually completes.
     */
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)

    /*
     * ML Kit text recognition, bundled rather than Play-Services-backed: the screen agent needs OCR
     * for apps that expose no accessibility text, and it must work with no network at all.
     */
    implementation(libs.androidx.mlkit.text)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    /*
     * Tests.
     *
     * The unit tests cover the pure logic — parsers, JSON round-trips, the WAV container, time
     * handling — and run on the JVM with no device, which is why so much of it was extracted out of
     * the Android-bound classes. `org.json` is the real implementation: the android.jar stub throws
     * on the unit-test classpath, and stubbing it out would make the round-trip tests meaningless.
     */
    testImplementation(libs.junit)
    testImplementation(libs.org.json)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)

    debugImplementation(libs.androidx.ui.tooling)
}
