plugins {
    id("org.jetbrains.kotlin.jvm")
}

group = "com.moldyhub.clonehub.cve"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test-junit"))
}

tasks.test {
    useJUnit()
}
