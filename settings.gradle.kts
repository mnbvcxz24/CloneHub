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
        maven { url = uri("https://www.jitpack.io") }
    }
}

rootProject.name = "CloneHub"

include(":app", ":cve", ":Bcore", ":black-reflection", ":compiler")
project(":cve").projectDir = file("cve")
project(":Bcore").projectDir = file("third_party/NewBlackbox/Bcore")
project(":black-reflection").projectDir = file("third_party/NewBlackbox/black-reflection")
project(":compiler").projectDir = file("third_party/NewBlackbox/compiler")
