import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
    // Applied to the two published modules below, not to the root or the example. Kotlin is here
    // only so its classes are on this script's classpath: the publish plugin describes a
    // `KotlinJvm` publication and has to be able to see the Kotlin plugin to do it.
    kotlin("jvm") apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
    id("org.jetbrains.dokka") version "2.2.0" apply false
}

val repository = "https://github.com/lldata/quarkus-kotlin-typescript-rest-client-generator"

/** A `-SNAPSHOT` is not signed and Central does not ask it to be. */
val isSnapshot = version.toString().endsWith("SNAPSHOT")

/**
 * Signing needs a key, and `publishToMavenLocal` is the documented way to try the library out — so
 * it must not require one. Signing therefore turns on when the key is there rather than on the
 * version being a release; the publish workflow refuses to run at all without the secrets, which is
 * where that belongs, since it is the only place a missing key means something has gone wrong.
 */
val canSign =
    listOf("signingInMemoryKey", "signingInMemoryKeyPassword")
        .all { !project.findProperty(it)?.toString().isNullOrBlank() }

/** What is published, and what each one is for. The example is not published. */
val published =
    mapOf(
        "generator" to
            ("quarkus-kotlin-typescript-rest-client-generator" to
                "Generates a TypeScript client from Quarkus/JAX-RS Kotlin resources, by reading the compiled classes."),
    )

published.forEach { (module, coordinates) ->
    val (artifact, summary) = coordinates
    project(":$module") {
        apply(plugin = "com.vanniktech.maven.publish")
        apply(plugin = "org.jetbrains.dokka")

        // The module's own script applies Kotlin, and this root script is configured before it is,
        // so the publication cannot be described until the plugin is actually there — `KotlinJvm`
        // asks for it by name.
        plugins.withId("org.jetbrains.kotlin.jvm") {
        configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            // Deliberately not `automaticRelease`: the upload lands in the Portal and waits for a
            // human to press Publish. A released version is permanent and cannot be recalled, so it
            // stays a deliberate act rather than a consequence of pushing a tag.
            publishToMavenCentral()
            if (!isSnapshot && canSign) signAllPublications()

            // Central requires a `-javadoc` artifact, and `withJavadocJar()` produces an empty one
            // for a Kotlin-only source set, so Dokka's HTML output stands in for it.
            configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"), sourcesJar = true))

            coordinates(rootProject.group.toString(), artifact, rootProject.version.toString())

            pom {
                name.set(artifact)
                description.set(summary)
                url.set(repository)
                organization {
                    name.set("LL Data ApS")
                    url.set("https://lldata.dk")
                }
                issueManagement {
                    system.set("GitHub")
                    url.set("$repository/issues")
                }
                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://raw.githubusercontent.com/lldata/quarkus-kotlin-typescript-rest-client-generator/main/LICENSE")
                        distribution.set("repo")
                    }
                }
                scm {
                    url.set(repository)
                    connection.set("scm:git:$repository.git")
                    developerConnection.set("scm:git:ssh://git@github.com/lldata/quarkus-kotlin-typescript-rest-client-generator.git")
                }
                developers {
                    developer {
                        id.set("lldata")
                        name.set("Lasse Lindgard")
                        email.set("lasse@lldata.dk")
                    }
                }
            }
        }
        }
    }
}
