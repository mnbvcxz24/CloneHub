plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.moldyhub.clonehub"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.moldyhub.clonehub"
        minSdk = 29
        targetSdk = 28
        versionCode = 23
        versionName = "0.1.0-alpha23"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// BlackBox is intentionally excluded from the Alpha23 APK for startup isolation.
// The upstream source remains in third_party for later, controlled integration.
dependencies {
    implementation(project(":cve"))
    testImplementation("junit:junit:4.13.2")
}
