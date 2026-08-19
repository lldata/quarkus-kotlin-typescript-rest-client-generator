package dk.lldata.tsclientgen.example.codegen

/**
 * `./gradlew :example:backend:generateApiClient` — writes the client and the transport it imports.
 *
 * The only thing that writes. `GeneratedClientDriftTest` compares, so a stale committed file fails
 * the build instead of being quietly fixed by whoever runs the tests next.
 */
fun main() {
    val generator = ExampleClient.generator()
    generator.writeRuntimeTo(ExampleClient.runtimeFile)
    generator.writeTo(ExampleClient.clientFile)
    generator.writeTemporalTo(ExampleClient.temporalFile, clientImport = "./client.js")
    generator.writeStampTo(ExampleClient.stampFile)
    println("Wrote ${ExampleClient.clientFile.parentFile.list()?.sorted()?.joinToString(", ")} to ${ExampleClient.clientFile.parent}")
}
