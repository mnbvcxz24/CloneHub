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
        versionCode = 3
        versionName = "0.4.0-alpha01"
    }
    flavorDimensions += "edition"
    productFlavors {
        create("lite") {
            dimension = "edition"
            applicationIdSuffix = ".lite"
            resValue("string", "app_name", "Fuku Lite")
        }
        create("universal") {
            dimension = "edition"
            applicationIdSuffix = ".universal"
            resValue("string", "app_name", "Fuku Universal")
        }
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

val cloneRuntimeDex = layout.buildDirectory.file("generated/cloneRuntimeAssets/clone-runtime.dex")
val runtimeApk = project(":clone-runtime").layout.buildDirectory.file("outputs/apk/debug/clone-runtime-debug.apk")
val extractCloneRuntimeDex by tasks.registering {
    dependsOn(":clone-runtime:assembleDebug")
    inputs.file(runtimeApk)
    outputs.file(cloneRuntimeDex)
    doLast {
        val apk = runtimeApk.get().asFile
        check(apk.isFile) { "Clone runtime APK missing: " + apk.absolutePath }
        val output = cloneRuntimeDex.get().asFile
        output.parentFile.mkdirs()
        java.util.zip.ZipFile(apk).use { archive ->
            val dex = archive.getEntry("classes.dex") ?: error("Clone runtime APK has no classes.dex.")
            archive.getInputStream(dex).use { input ->
                output.outputStream().use { target -> input.copyTo(target) }
            }
        }
    }
}
android.sourceSets.getByName("main").assets.srcDir(cloneRuntimeDex.map { it.asFile.parentFile })
tasks.named("preBuild").configure { dependsOn(extractCloneRuntimeDex) }
