plugins {
    kotlin("jvm") version "2.0.21"
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
