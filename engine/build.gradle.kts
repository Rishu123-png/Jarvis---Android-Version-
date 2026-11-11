plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

/**
 * The engine: what a routine *is*, and what it is allowed to do — with no Android in it.
 *
 * Everything here is arithmetic and rules: the action catalogue, the risk ladder, the fingerprint,
 * the reflection verdicts, the retry policy, the rollback planner, and the two pure rankers
 * (assist-text filtering and memory retrieval). Not one file imports `android.*`, which is the point:
 * these are the decisions that must be provable on a JVM, and `tools/guard.py` fails the build if an
 * `import android.` appears anywhere in this module.
 *
 * It is an Android *library* rather than a pure Kotlin one for a single mundane reason: these files
 * speak `org.json`, and on a pure-JVM module that would mean shipping a second copy of the class into
 * the APK. The platform's own is the one that should be used.
 *
 * The executor (`AutomationEngine`), the accessibility service and the store stay in `:app`, because
 * they touch a phone.
 */
android {
    namespace = "com.jarvis.assistant.engine"
    compileSdk = 34

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // The platform provides org.json at runtime; this is only what the compiler needs to see.
    compileOnly(libs.org.json)
}
