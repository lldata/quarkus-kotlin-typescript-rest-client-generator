pluginManagement {
    val quarkusPluginVersion: String by settings

    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("jvm") version "2.4.20"
        id("io.quarkus") version quarkusPluginVersion
        id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
        kotlin("plugin.allopen") version "2.4.20"
    }
}

plugins {
    // Provisions the JDK the toolchains ask for, so a checkout builds on whatever JDK is installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "quarkus-kotlin-typescript-rest-client-generator"

// Two annotations with no dependencies. They go on your resources, so they go on your runtime
// classpath — separate from the generator, which does not.

include("generator")

// The example is a full consumer of the published library — a Quarkus resource layer, the generated
// client and a page that calls it — so it is built and tested here rather than described in prose.
include("example:backend")
