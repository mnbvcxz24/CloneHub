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
        // Mirrors for the legacy, open-source VirtualApp build dependencies.
        maven { url = uri("https://artifactory.appodeal.com/appodeal-public/") }
        maven { url = uri("https://verve.jfrog.io/artifactory/verve-gradle-release/") }
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
