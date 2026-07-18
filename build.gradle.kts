plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
}

application {
    mainClass = "io.github.takahirom.cochange.MainKt"
}

group = "io.github.takahirom"
version = (findProperty("cochangeVersion") as String?) ?: "0.0.0-dev"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.github.ajalt.clikt:clikt:4.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.distTar {
    compression = Compression.GZIP
    archiveExtension = "tar.gz"
}

// Stamp the build version into the jar manifest so `cochange --version` can report the release tag.
tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
