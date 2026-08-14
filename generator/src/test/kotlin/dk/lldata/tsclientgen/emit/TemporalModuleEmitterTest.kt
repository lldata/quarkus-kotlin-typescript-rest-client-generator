package dk.lldata.tsclientgen.emit

import dk.lldata.tsclientgen.GeneratorOptions
import dk.lldata.tsclientgen.Scalar
import dk.lldata.tsclientgen.ScalarStyle
import dk.lldata.tsclientgen.testOptions
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class TemporalModuleEmitterTest {
    private fun emit(
        style: ScalarStyle,
        clientImport: String = "./client.js",
    ): String? {
        val options = testOptions.copy(scalars = GeneratorOptions.scalars(style))
        val scalars = listOf(options.scalars.getValue("java.time.Instant"), options.scalars.getValue("kotlin.String"))
        return TemporalModuleEmitter(options).emit(scalars, clientImport)
    }

    /**
     * ISO-8601 is what both sides speak, so the conversion is `Temporal.X.from` one way and
     * `toString()` the other — no conversion layer, and nothing per-field at runtime.
     */
    @Test
    fun `converts a named scalar in both directions`() {
        val emitted = emit(ScalarStyle.Named)!!

        emitted shouldContain "instant: (value: Instant): Temporal.Instant => Temporal.Instant.from(value)"
        emitted shouldContain "value.toString() as Instant"
    }

    /** A branded scalar has a companion, so the wire-bound direction is validated rather than cast. */
    @Test
    fun `goes through the companion when there is one to go through`() {
        val emitted = emit(ScalarStyle.ValueObject)!!

        emitted shouldContain "Instant.from(value.toString())"
        emitted shouldNotContain "as Instant"
    }

    /**
     * `namedImport`, so Poet collects it into an import at the top. `addCode` appends at the end of
     * the file, where an import is legal and reads like a mistake.
     */
    @Test
    fun `imports the client at the top, from where it was told`() {
        val emitted = emit(ScalarStyle.Named, clientImport = "../api/client.js")!!

        emitted.lines().first { it.startsWith("import") } shouldContain """from "../api/client.js""""
    }

    /** A scalar with no Temporal equivalent contributes nothing, and no file is a fine answer. */
    @Test
    fun `emits nothing when nothing converts`() {
        val options = testOptions.copy(scalars = GeneratorOptions.scalars(ScalarStyle.Named))
        val noDates = listOf(options.scalars.getValue("kotlin.String"), Scalar.named("Isbn", "string"))

        TemporalModuleEmitter(options).emit(noDates, "./client.js").shouldBeNull()
        emit(ScalarStyle.Inline).shouldBeNull()
    }
}
