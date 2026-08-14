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

// No dependencies, deliberately. These two annotations go on your resources, which means they go on
// your runtime classpath — so this artifact must not drag the generator, kotlin-reflect or a
// TypeScript writer into a deployed application.
dependencies {
    compileOnly(kotlin("stdlib"))
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "quarkus-kotlin-typescript-rest-client-annotations"
            from(components["java"])
        }
    }
}
