import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.20"
    application
}

group = "io.github.relvl"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

dependencies {
    // Kotlin runtime reflection
    implementation(kotlin("reflect"))

    // Configuration serialization
    implementation("tools.jackson.module:jackson-module-kotlin:3.2.0")

    // Command line interface
    implementation("info.picocli:picocli:4.7.7")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.18")
    runtimeOnly("ch.qos.logback:logback-classic:1.6.3")

    // Tests
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
application {
    mainClass.set("io.github.relvl.deobscura.MainKt")
}
