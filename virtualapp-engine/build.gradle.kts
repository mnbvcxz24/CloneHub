plugins {
    id("com.android.library")
}

android {
    namespace = "com.lody.virtual"
    compileSdk = 35
    ndkVersion = "21.4.7075529"

    defaultConfig {
        minSdk = 21
        targetSdk = 28
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("../third_party/VirtualApp/lib/src/main/AndroidManifest.xml")
            java.srcDirs("../third_party/VirtualApp/lib/src/main/java")
            aidl.srcDirs("../third_party/VirtualApp/lib/src/main/aidl")
            res.srcDirs("../third_party/VirtualApp/lib/src/main/res")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("../third_party/VirtualApp/lib/src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api("me.weishu.exposed:exposed-core:0.8.1") {
        exclude(group = "me.weishu", module = "free_reflection")
        exclude(group = "me.weishu", module = "epic")
        exclude(group = "me.weishu.exposed", module = "exposed-xposedapi")
    }
    implementation("me.weishu:free_reflection:3.0.1")
    implementation("me.weishu:epic:0.11.1")
    implementation("me.weishu.exposed:exposed-xposedapi:0.4.6")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
