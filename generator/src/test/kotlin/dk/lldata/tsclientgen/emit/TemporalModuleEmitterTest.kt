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
        temporalImport: String? = null,
    ): String? {
        val options = testOptions.copy(scalars = GeneratorOptions.scalars(style))
        val scalars = listOf(options.scalars.getValue("java.time.Instant"), options.scalars.getValue("kotlin.String"))
        return TemporalModuleEmitter(options).emit(scalars, clientImport, temporalImport)
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

    /**
     * `Temporal` is a global only where TypeScript declares one: 7.0 does with `lib: "esnext"`, 5.9
     * does not, and the polyfills export it as a module member rather than declaring a global. So a
     * project on an older TypeScript needs the module to import it, and saying `lib: "esnext"` at
     * them — which is what the README used to do — cannot work however they set it.
     */
    @Test
    fun `imports Temporal when told where it lives`() {
        val emitted = emit(ScalarStyle.Named, temporalImport = "@js-temporal/polyfill")!!

        emitted shouldContain """import { Temporal } from "@js-temporal/polyfill";"""
        emitted shouldContain "Temporal.Instant.from(value)"
    }

    /** The default stays the global, which is what TypeScript 7 declares and the example uses. */
    @Test
    fun `uses the global Temporal by default`() {
        val emitted = emit(ScalarStyle.Named)!!

        emitted shouldNotContain "import { Temporal }"
        emitted shouldContain "Temporal.Instant.from(value)"
    }

    /** Where the requirement is discovered is the file that fails, so it says so itself. */
    @Test
    fun `says in the file what it needs`() {
        emit(ScalarStyle.Named)!! shouldContain "TypeScript's own lib declares it from"
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
