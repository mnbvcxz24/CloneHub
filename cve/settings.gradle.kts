pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "clonehub-cve-core"
