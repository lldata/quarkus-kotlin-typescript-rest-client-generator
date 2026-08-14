package dk.lldata.tsclientgen.example.codegen

import dk.lldata.tsclientgen.ClientGenerator
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The two gates every project using the generator should have.
 *
 * Without the drift test a backend change lands green and the client disagrees with it until
 * someone hits the mismatch at runtime. Without the lint the generator emits a client that
 * compiles and misleads.
 */
class GeneratedClientDriftTest {
    private val generator = ExampleClient.generator()

    @Test
    fun `the committed client matches the resources it was generated from`() {
        val drift = generator.driftAgainst(ExampleClient.clientFile)

        drift shouldBe null
    }

    @Test
    fun `the committed transport matches the one the generator ships`() {
        ExampleClient.runtimeFile.readText() shouldBe ClientGenerator.runtimeSource()
    }

    /** Generated from the same model, so it drifts the same way and needs the same gate. */
    @Test
    fun `the committed Temporal module matches the client it converts`() {
        ExampleClient.temporalFile.readText() shouldBe generator.temporalSource(clientImport = "./client.js")
    }

    @Test
    fun `the API keeps the contract the client is generated from`() {
        generator.lint().check()
    }
}
