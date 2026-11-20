plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.moldyhub.fuku"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.moldyhub.fuku"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "0.4.4-alpha08"
    }
    buildTypes {
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}
dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.core.ktx)
    implementation(libs.arsclib)
    implementation(libs.apksig)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
