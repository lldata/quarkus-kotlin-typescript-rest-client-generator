import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion

plugins {
    kotlin("jvm")
    `java-library`
    id("org.jlleitschuh.gradle.ktlint")
}

repositories {
    mavenCentral()
}

dependencies {

    // The TypeScript writer. A fork of outfoxx/typescriptpoet, which stopped at 1.1.2 in 2021 —
    // this adds the Kotlin DSL, the object-literal getter a lazy call tree is made of, and a writer
    // that breaks a call's argument list on width.
    api("dk.lldata:typescriptpoet:2.0.0")
    // Versioned explicitly. `kotlin("reflect")` alone emits a dependency with no `<version>` in the
    // POM and puts the number in `dependencyManagement` instead, which the Central Portal rejects
    // during validation — it was what failed the 1.0.0-alpha5 deployment. Taken from the applied
    // plugin rather than written as a literal, so it cannot drift from the version
    // `settings.gradle.kts` pins and Dependabot bumps.
    api(kotlin("reflect", getKotlinPluginVersion()))

    // Read from the consumer's classes, never loaded by this library on its own: whichever versions
    // the consuming project compiles against are the ones the annotations are read from.
    compileOnly("jakarta.ws.rs:jakarta.ws.rs-api:4.0.0")

    // Through the BOM rather than a literal, because Jackson's modules stopped sharing a version
    // number: `jackson-annotations` publishes 2.21 and 2.22, `jackson-databind` publishes 2.22.0
    // through 2.22.2. The BOM's Gradle metadata aligns the family to one version, so any pair of
    // literals resolves to a coordinate that does not exist — 2.22 of databind, or 2.22.2 of
    // annotations, both 404. The platform is the only version this project can state and the only
    // one a bump has to move.
    compileOnly(platform("com.fasterxml.jackson:jackson-bom:2.22.2"))
    compileOnly("com.fasterxml.jackson.core:jackson-annotations")

    testImplementation("jakarta.ws.rs:jakarta.ws.rs-api:4.0.0")
    testImplementation(platform("com.fasterxml.jackson:jackson-bom:2.22.2"))
    testImplementation("com.fasterxml.jackson.core:jackson-annotations")
    // Only so a fixture can carry `@JsonNaming`, which lives in databind. The library itself reads
    // it by qualified name and depends on none of this.
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
    // Only so the fixtures can declare `RestResponse<T>` and prove the wrapper is unwrapped by the
    // real class rather than by a stand-in with the same name.
    testImplementation("io.quarkus.resteasy.reactive:resteasy-reactive-common:3.39.1")
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
