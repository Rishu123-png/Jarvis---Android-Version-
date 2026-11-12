import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
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

/*
 * The offline voice model ("Hey Jarvis") is packed into the APK at build time, so installing the app
 * is enough. It is ~40 MB and is never committed: the folder is git-ignored and rebuilt on demand.
 *
 * If the download fails (no network on the build machine), the build still succeeds and warns; the
 * app then downloads the model itself the first time the operator turns the wake word on.
 */
/*
 * The offline voice model travels INSIDE the APK.
 *
 * The operator downloads one file and the wake word works — no second download, no first-run
 * network call, no "install the model" screen. That only holds if the fetch cannot quietly fail,
 * so CI passes -PrequireVoskModel=true and an APK built without the model is not shippable.
 * A local build stays forgiving so it can be compiled without a network.
 */
val requireVoskModel = providers.gradleProperty("requireVoskModel").orNull?.toBoolean() == true
val voskModelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
val voskAssetRoot = layout.buildDirectory.dir("generated/vosk-assets").get().asFile
val voskAssetDir = File(voskAssetRoot, "model-en-us")

val fetchVoskModel by tasks.registering {
    outputs.dir(voskAssetRoot)
    doLast {
        if (File(voskAssetDir, "am/final.mdl").isFile) return@doLast
        val zip = layout.buildDirectory.file("vosk/model.zip").get().asFile
        val staging = layout.buildDirectory.dir("vosk/unzipped").get().asFile
        try {
            zip.parentFile.mkdirs()
            if (!zip.isFile || zip.length() == 0L) {
                logger.lifecycle("Fetching the offline voice model (~40 MB) for the APK ...")
                URL(voskModelUrl).openStream().use { input ->
                    zip.outputStream().use { input.copyTo(it) }
                }
            }
            staging.deleteRecursively()
            staging.mkdirs()
            ZipInputStream(zip.inputStream()).use { zin ->
                generateSequence { zin.nextEntry }.forEach { entry ->
                    val out = File(staging, entry.name)
                    require(out.canonicalPath.startsWith(staging.canonicalPath)) { "unsafe archive path" }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile.mkdirs()
                        out.outputStream().use { zin.copyTo(it) }
                    }
                    zin.closeEntry()
                }
            }
            val unpacked = File(staging, "vosk-model-small-en-us-0.15")
            check(File(unpacked, "am/final.mdl").isFile) { "model folder is missing am/final.mdl" }
            voskAssetDir.deleteRecursively()
            unpacked.copyRecursively(voskAssetDir, overwrite = true)
            logger.lifecycle("Offline voice model bundled into the APK.")
        } catch (e: Exception) {
            if (requireVoskModel) {
                error("The offline voice model had to be bundled for this build but was not: ${e.message}")
            }
            logger.warn("Offline voice model NOT bundled (${e.message}). The app will download it on first use.")
            zip.delete()
        }
    }
}

/*
 * Shizuku travels inside the JARVIS APK.
 *
 * It is the one companion app JARVIS cannot do without: it lends the shell identity that flips Wi-Fi,
 * Bluetooth, data and airplane mode. Making the operator find a second download was the last rough
 * edge, so the release APK is fetched here and shipped inside ours.
 *
 * Same rules as the voice model: never committed, fetched into the build folder, and a build machine
 * with no network still succeeds and warns — the app then downloads Shizuku itself when asked.
 * The version and byte size are pinned, so a silently swapped asset is rejected rather than shipped.
 */
val shizukuVersion = "13.6.0"
val shizukuApkBytes = 2_571_773L
/*
 * Why the bundled app (13.6.0) and the API artifact (13.1.5, in gradle/libs.versions.toml) are not
 * the same number, on purpose.
 *
 * 13.1.5 is the newest `dev.rikka.shizuku` the author publishes to Maven Central — the library's own
 * changelog ends there — so asking for 13.6.0 in the dependency block would not resolve and would
 * fail the build. Shizuku guarantees that a newer manager keeps serving an older client, which is
 * exactly this pairing: the operator gets the newest Shizuku fixes, and the app talks to it through
 * the newest API that exists. The two pins are checked against the phone's download URL by
 * tools/guard.py, so this comment cannot quietly become untrue.
 */
val shizukuUrl = "https://github.com/RikkaApps/Shizuku/releases/download/v" + shizukuVersion + "/" +
    "shizuku-v" + shizukuVersion + ".r1086.2650830c-release.apk"
/*
 * CI passes -PrequireShizuku=true, so an APK that quietly shipped without Shizuku cannot be released.
 * Local builds stay forgiving: no network, no download, the app fetches it on the phone instead.
 */
val requireShizuku = providers.gradleProperty("requireShizuku").orNull?.toBoolean() == true
val shizukuAssetRoot = layout.buildDirectory.dir("generated/shizuku-assets").get().asFile
val shizukuAssetFile = File(shizukuAssetRoot, "shizuku/shizuku.apk")

val fetchShizuku by tasks.registering {
    outputs.dir(shizukuAssetRoot)

    doLast {
        if (shizukuAssetFile.isFile && shizukuAssetFile.length() == shizukuApkBytes) return@doLast

        val staged = layout.buildDirectory.file("shizuku/download.apk").get().asFile
        try {
            shizukuAssetFile.parentFile.mkdirs()
            staged.parentFile.mkdirs()
            logger.lifecycle("Fetching Shizuku $shizukuVersion for the APK ...")

            fun openFollowingRedirects(start: String): HttpURLConnection {
                var target = start
                repeat(6) {
                    val connection =
                        (URL(target).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 20_000
                            readTimeout = 120_000
                            instanceFollowRedirects = false
                            useCaches = false
                            setRequestProperty("Accept", "application/octet-stream")
                            setRequestProperty("User-Agent", "JARVIS-build")
                        }
                    val code = connection.responseCode
                    if (code in 300..399) {
                        target = connection.getHeaderField("Location")
                            ?: error("redirect without a location")
                    } else {
                        check(code in 200..299) { "HTTP $code from $target" }
                        return connection
                    }
                }
                error("too many redirects")
            }

            val connection = openFollowingRedirects(shizukuUrl)
            connection.inputStream.use { input ->
                staged.outputStream().use { input.copyTo(it) }
            }

            check(staged.length() == shizukuApkBytes) {
                "expected $shizukuApkBytes bytes, got ${staged.length()}"
            }
            staged.copyTo(shizukuAssetFile, overwrite = true)
            logger.lifecycle("Shizuku $shizukuVersion bundled into the APK.")
        } catch (e: Exception) {
            if (requireShizuku) {
                error("Shizuku had to be bundled for this build but was not: ${e.message}")
            }
            logger.warn(
                "Shizuku NOT bundled (${e.message}). The app will download it when the operator asks.",
            )
            staged.delete()
            shizukuAssetFile.delete()
        }
    }
}

// Everything that reads the assets folder (merge, generate, and lint's model writers) must run after
// the fetch; declaring it here keeps Gradle's ordering checks satisfied.
tasks.configureEach {
    if (name.contains("Assets", ignoreCase = true) || name.contains("lint", ignoreCase = true)) {
        dependsOn(fetchVoskModel, fetchShizuku)
    }
}

android {
    namespace = "com.jarvis.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.assistant"
        minSdk = 29
        targetSdk = 34
        versionCode = 40
        versionName = "1.0.1"

        /*
         * Everything JARVIS needs travels inside the APK, and this flag is what makes that true
         * rather than aspirational.
         *
         * The voice model (~40 MB) and the Shizuku APK are fetched at *build* time — CI passes
         * -PrequireVoskModel=true -PrequireShizuku=true, so a build without them fails instead of
         * shipping. With this true, the runtime download fallbacks are closed: an app that quietly
         * fetches 40 MB on first use is a second download the operator did not agree to, and the
         * one thing this project promises is that installing the APK is the whole install.
         */
        buildConfigField("boolean", "BUNDLED_ONLY", "true")

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
            /*
             * R8 on, and resources shrunk with it.
             *
             * The app ships a 40 MB voice model and a 2.5 MB Shizuku, so the code is not the bulk of
             * the download — but the *startup* cost of unshrunk dex is real, and a 300-class app with
             * a thousand library classes pays for library code it never calls. Minifying also lets
             * the startup profile in src/main/baseline-prof.txt do anything at all.
             *
             * Debug stays unminified, so a crash in development keeps its own names.
             */
            isMinifyEnabled = true
            isShrinkResources = true
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

    /*
     * Android lint runs on every build in CI, but it does not stop one -- yet.
     *
     * This app has never been through lint, and a linter's first pass over a five-year-old codebase
     * is a triage exercise, not a verdict: the largest group is always `MissingPermission`, which
     * fires wherever a permission is checked by hand with a graceful fallback instead of by an
     * annotation. Turning that into a red build on day one would teach everyone to ignore it.
     *
     * So errors are reported, not fatal. When the report is clean (or a baseline is committed),
     * set `abortOnError = true` here and drop `continue-on-error` from the CI step: the gate then
     * exists for real.
     */
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        warningsAsErrors = false
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
    // Everything a routine *is* lives here: pure rules, no Android.
    implementation(project(":engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    // Applies the bundled baseline profile at install time; without it the profile is inert.
    implementation(libs.androidx.profileinstaller)

    /*
     * Room, for the one thing that actually grows without bound: run history.
     *
     * Routines are a handful of small documents and stay JSON in SharedPreferences — a database for
     * eight rows would be ceremony. History is an append-only log that was being rewritten *whole*
     * into a preference string on every run, which is exactly the shape a table exists for.
     */
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

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
     * "Hey Jarvis", offline. Vosk is the only wake-word engine that is free, needs no account or
     * access key, and runs on the device. JNA is its native bridge. The model (~40 MB) is not in the
     * APK: the app downloads it once, the first time the operator turns the wake word on.
     */
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")

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

// The generated model and the bundled Shizuku APK are asset sources: they live in the build folder,
// never in src/.
android.sourceSets.getByName("main").assets.srcDir(voskAssetRoot)
android.sourceSets.getByName("main").assets.srcDir(shizukuAssetRoot)
