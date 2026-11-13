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
        versionCode = 2
        versionName = "0.1.0-alpha02"

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

// Controlled diagnostic: include Bcore as a build dependency,
// but keep BlackBoxRuntime as a no-op and do not initialize the engine.
dependencies {
    implementation(project(":Bcore"))
}
