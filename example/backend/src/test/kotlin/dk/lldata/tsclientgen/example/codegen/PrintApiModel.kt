package dk.lldata.tsclientgen.example.codegen

/**
 * `./gradlew :example:backend:printApiModel` — the API as the generator reads it.
 *
 * The fastest way to see what the emitter has to work with, and what a resource is still failing to
 * declare.
 */
fun main() {
    println(ExampleClient.generator().model.describe())
}
