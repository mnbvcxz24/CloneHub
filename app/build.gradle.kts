import java.util.zip.ZipFile

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
        versionCode = 11
        versionName = "0.4.4-alpha05"
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
val cloneRuntimeD8 by configurations.creating

dependencies {
    cloneRuntimeD8("com.android.tools:r8:8.7.18")
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

val runtimeAar = project(":clone-runtime").layout.buildDirectory.file("outputs/aar/clone-runtime-release.aar")
val runtimeClassesJar = layout.buildDirectory.file("intermediates/clone-runtime/classes.jar")
val runtimeDexDir = layout.buildDirectory.dir("intermediates/clone-runtime/dex")
val runtimeAssetsDir = layout.buildDirectory.dir("generated/cloneRuntimeAssets")

android.sourceSets.getByName("main").assets.srcDir(runtimeAssetsDir)

val generateCloneRuntimeDex by tasks.registering(JavaExec::class) {
    dependsOn(":clone-runtime:bundleReleaseAar")
    classpath = cloneRuntimeD8
    mainClass.set("com.android.tools.r8.D8")
    doFirst {
        val aar = runtimeAar.get().asFile
        check(aar.isFile) { "Clone runtime AAR was not built: $aar" }
        val jarOut = runtimeClassesJar.get().asFile
        jarOut.parentFile.mkdirs()
        ZipFile(aar).use { zip ->
            val entry = zip.getEntry("classes.jar") ?: error("Clone runtime AAR has no classes.jar")
            zip.getInputStream(entry).use { input -> jarOut.outputStream().use { input.copyTo(it) } }
        }
        val dexDir = runtimeDexDir.get().asFile
        dexDir.deleteRecursively()
        dexDir.mkdirs()
        val androidJar = File(android.sdkDirectory, "platforms/android-35/android.jar")
        check(androidJar.isFile) { "Android 35 android.jar not found: $androidJar" }
        args("--min-api", "26", "--lib", androidJar.absolutePath,
            "--output", dexDir.absolutePath, jarOut.absolutePath)
    }
    doLast {
        val dex = File(runtimeDexDir.get().asFile, "classes.dex")
        check(dex.isFile && dex.length() > 0L) { "D8 did not produce clone runtime classes.dex" }
        val assets = runtimeAssetsDir.get().asFile
        assets.mkdirs()
        dex.copyTo(File(assets, "clone-runtime.dex"), overwrite = true)
    }
}

tasks.named("preBuild").configure { dependsOn(generateCloneRuntimeDex) }
