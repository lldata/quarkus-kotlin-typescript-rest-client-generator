plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
    id("org.jlleitschuh.gradle.ktlint")
}

group = "dk.lldata"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    api(project(":annotations"))

    // The TypeScript writer. A fork of outfoxx/typescriptpoet, which stopped at 1.1.2 in 2021 —
    // the alpha adds the Kotlin DSL and the object-literal getter a lazy call tree is made of.
    // Unpublished, so the jar is vendored and committed; see README, "The TypeScriptPoet jar".
    api(files(rootProject.file("libs/typescriptpoet-2.0.0-alpha6.jar")))
    api(kotlin("reflect"))

    // Read from the consumer's classes, never loaded by this library on its own: whichever versions
    // the consuming project compiles against are the ones the annotations are read from.
    compileOnly("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")
    compileOnly("com.fasterxml.jackson.core:jackson-annotations:2.17.0")

    testImplementation("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")
    testImplementation("com.fasterxml.jackson.core:jackson-annotations:2.17.0")
    // Only so the fixtures can declare `RestResponse<T>` and prove the wrapper is unwrapped by the
    // real class rather than by a stand-in with the same name.
    testImplementation("io.quarkus.resteasy.reactive:resteasy-reactive-common:3.38.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation("io.kotest:kotest-assertions-core-jvm:6.2.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // A library floor, not a build floor: consumers on Java 17 and Kotlin 2.0 can use the jar
        // even though it is built with a newer toolchain. `-Xjdk-release` makes the compiler check
        // it against the 17 API rather than only setting the class-file version.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

java {
    withSourcesJar()
    // There is no Java source here, but Gradle still validates that the two compilers agree.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "quarkus-kotlin-typescript-rest-client-generator"
            from(components["java"])
        }
    }
}
