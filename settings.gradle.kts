pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // VirtualApp's legacy Xposed support artifacts were originally published to JCenter.
        // Use maintained mirrors because JCenter itself is retired.
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/jcenter") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/nexus/content/groups/public") }
        maven { url = uri("https://developer.huawei.com/repo/") }
        maven { url = uri("https://www.jitpack.io") }
    }
}

rootProject.name = "CloneHub"

include(":app", ":cve", ":virtualapp-engine")
project(":cve").projectDir = file("cve")
