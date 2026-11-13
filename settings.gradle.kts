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
        maven("https://jitpack.io")
    }
}

rootProject.name = "CloneHub"

include(":app", ":Bcore", ":black-reflection", ":compiler")
project(":Bcore").projectDir = file("third_party/Blacks-BlackBox/Bcore")
project(":black-reflection").projectDir = file("third_party/Blacks-BlackBox/black-reflection")
project(":compiler").projectDir = file("third_party/Blacks-BlackBox/compiler")
