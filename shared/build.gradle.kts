plugins {
    id("com.android.library")
    // No "org.jetbrains.kotlin.android" here -- AGP 9+ compiles Kotlin itself, see root
    // build.gradle.kts for why.
}

android {
    namespace = "com.pedro.heartratewatch.shared"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The GPS distance tracker (LocationDistanceTracker) is used by both the watch session service
    // and the phone's own tracking service.
    api("com.google.android.gms:play-services-location:21.3.0")
}
