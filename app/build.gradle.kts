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
        versionCode = 12
        versionName = "0.1.0-alpha12"

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

// Keep BlackBox inactive while integrating the clean-room CVE core.
dependencies {
    implementation(project(":Bcore"))
    implementation(project(":cve"))
    testImplementation("junit:junit:4.13.2")
}
