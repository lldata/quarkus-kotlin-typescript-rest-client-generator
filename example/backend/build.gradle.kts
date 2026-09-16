import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.allopen")
    id("io.quarkus")
    id("org.jlleitschuh.gradle.ktlint")
}

group = "dk.lldata.tsclientgen.example"

repositories {
    mavenCentral()
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

dependencies {
    implementation(enforcedPlatform("$quarkusPlatformGroupId:$quarkusPlatformArtifactId:$quarkusPlatformVersion"))
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-kotlin")
    implementation("io.quarkus:quarkus-smallrye-openapi")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    testImplementation(project(":generator"))

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("io.kotest:kotest-assertions-core-jvm:6.2.5")
}

// Quarkus needs its beans open; Kotlin classes are final by default.
allOpen {
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("jakarta.ws.rs.Path")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

/**
 * Writes `example/web/src/generated/`. The client is committed, and `GeneratedClientDriftTest`
 * fails the build when it stops matching the resources — so a backend change that breaks the client
 * is caught here rather than in a browser.
 */
tasks.register<JavaExec>("generateApiClient") {
    group = "codegen"
    description = "Generate the TypeScript API client from the JAX-RS resources."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dk.lldata.tsclientgen.example.codegen.GenerateApiClientKt")
    dependsOn("compileKotlin", "compileTestKotlin")
}

/** Prints the API as the generator reads it — the fastest way to see what the emitter works with. */
tasks.register<JavaExec>("printApiModel") {
    group = "codegen"
    description = "Print the JAX-RS API as the TypeScript client generator reads it."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dk.lldata.tsclientgen.example.codegen.PrintApiModelKt")
    dependsOn("compileKotlin", "compileTestKotlin")
}

// The drift test reads the compiled main classes, which `test` does not otherwise depend on.
tasks.test {
    dependsOn("compileKotlin")
}
