plugins {
    kotlin("jvm")
    `java-library`
    id("org.jlleitschuh.gradle.ktlint")
}

repositories {
    mavenCentral()
    // TypeScriptPoet's unreleased commits, built on demand. See the dependency below.
    maven("https://jitpack.io")
}

dependencies {
    api(project(":annotations"))

    // The TypeScript writer. A fork of outfoxx/typescriptpoet, which stopped at 1.1.2 in 2021 —
    // this adds the Kotlin DSL, the object-literal getter a lazy call tree is made of, and a writer
    // that breaks a call's argument list on width.
    // Pinned to a commit rather than a release: the import-wrapping fix this emitter's type-only
    // imports need landed the morning after alpha10 was tagged, and 2.0.0 is close enough that
    // cutting an alpha11 for one line of layout is the wrong trade. JitPack builds the commit on
    // demand — the group is the repository's, `com.github.lldata`, and goes back to `dk.lldata`
    // with the next release. Pinned, not `main-SNAPSHOT`, so a build here is reproducible.
    api("com.github.lldata:typescriptpoet:9a1a0e7")
    api(kotlin("reflect"))

    // Read from the consumer's classes, never loaded by this library on its own: whichever versions
    // the consuming project compiles against are the ones the annotations are read from.
    compileOnly("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")

    // Through the BOM rather than a literal, because Jackson's modules stopped sharing a version
    // number: `jackson-annotations` publishes 2.21 and 2.22, `jackson-databind` publishes 2.22.0
    // through 2.22.2. The BOM's Gradle metadata aligns the family to one version, so any pair of
    // literals resolves to a coordinate that does not exist — 2.22 of databind, or 2.22.2 of
    // annotations, both 404. The platform is the only version this project can state and the only
    // one a bump has to move.
    compileOnly(platform("com.fasterxml.jackson:jackson-bom:2.22.2"))
    compileOnly("com.fasterxml.jackson.core:jackson-annotations")

    testImplementation("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")
    testImplementation(platform("com.fasterxml.jackson:jackson-bom:2.22.2"))
    testImplementation("com.fasterxml.jackson.core:jackson-annotations")
    // Only so a fixture can carry `@JsonNaming`, which lives in databind. The library itself reads
    // it by qualified name and depends on none of this.
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
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
