pluginManagement {
    val quarkusPluginVersion: String by settings

    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("jvm") version "2.4.21"
        id("io.quarkus") version quarkusPluginVersion
        id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
        kotlin("plugin.allopen") version "2.4.21"
        // Here rather than in the root build, which CODEOWNERS holds for its publishing
        // configuration: a Dokka bump is a docs-tool bump, and `check` builds the Javadoc jar
        // with it, so the gate verifies it like any other.
        id("org.jetbrains.dokka") version "2.3.0"
    }
}

plugins {
    // Provisions the JDK the toolchains ask for, so a checkout builds on whatever JDK is installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "quarkus-kotlin-typescript-rest-client-generator"

include("generator")

// The example is a full consumer of the published library — a Quarkus resource layer, the generated
// client and a page that calls it — so it is built and tested here rather than described in prose.
include("example:backend")
