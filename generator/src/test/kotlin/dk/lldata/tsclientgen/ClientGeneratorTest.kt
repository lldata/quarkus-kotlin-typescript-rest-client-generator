package dk.lldata.tsclientgen

import dk.lldata.tsclientgen.fixture.OpsResource
import dk.lldata.tsclientgen.format.SourceFormatter
import dk.lldata.tsclientgen.model.ApiModelReader
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ClientGeneratorTest {
    @field:TempDir
    lateinit var target: File

    private fun generator(formatter: SourceFormatter = SourceFormatter.Passthrough) =
        ClientGenerator(testOptions, formatter) { fixtureModel() }

    @Test
    fun `writes the client and the runtime it imports`() {
        val client = generator().writeTo(File(target, "generated/client.ts"))
        val runtime = generator().writeRuntimeTo(File(target, "generated/client-runtime.ts"))

        client.readText() shouldContain "export function createApi"
        runtime.readText() shouldContain "export async function apiRequest"
    }

    /**
     * Without this, a backend change lands green and the client disagrees with it until someone
     * hits the mismatch at runtime.
     */
    @Test
    fun `reports no drift against what it just wrote`() {
        val written = generator().writeTo(File(target, "client.ts"))

        generator().driftAgainst(written).shouldBeNull()
    }

    /** The first differing line, not a whole-file diff: the fix is always to regenerate. */
    @Test
    fun `names the first line where the committed file disagrees`() {
        val written = generator().writeTo(File(target, "client.ts"))
        written.writeText(written.readText().replace("export type Isbn = string;", "export type Isbn = number;"))

        val drift = generator().driftAgainst(written)!!

        drift shouldContain "is stale at line"
        drift shouldContain "committed:   export type Isbn = number;"
        drift shouldContain "regenerated: export type Isbn = string;"
    }

    /**
     * The half that lets the server compare rather than only receive: the same composed value the
     * client carries, written where the backend can read it.
     */
    @Test
    fun `writes the stamp for the backend, and nothing when none is configured`() {
        val stamped = ClientGenerator(testOptions.copy(stamp = StampOptions(value = { it.short }))) { fixtureModel() }

        val written = stamped.writeStampTo(File(target, "resources/api-stamp.txt"))!!

        written.readText().trim() shouldBe stamped.digest.short
        written.readText().trim() shouldBe stamped.stamp
        generator().writeStampTo(File(target, "never.txt")).shouldBeNull()
    }

    /** Available whether or not a stamp is emitted, because it is the input to a project's own. */
    @Test
    fun `exposes the digest without a stamp being configured`() {
        val generator = generator()

        generator.stamp.shouldBeNull()
        generator.digest.contract shouldContain "root /api"
        generator.digest.modelHash.length shouldBe 64
    }

    @Test
    fun `reports a missing file as drift rather than throwing`() {
        generator().driftAgainst(File(target, "never-written.ts"))!! shouldContain "is missing"
    }

    /**
     * The write and the drift check run the same formatter, so they cannot disagree about what
     * "generated" means.
     */
    @Test
    fun `runs the emitted source through the formatter`() {
        val shouty = SourceFormatter { "// formatted\n$it" }

        val written = generator(shouty).writeTo(File(target, "client.ts"))

        written.readText().lines().first() shouldBe "// formatted"
        generator(shouty).driftAgainst(written).shouldBeNull()
    }

    /** Opt-in, so an empty client is a mistake to report rather than a file to write. */
    @Test
    fun `refuses to emit when no resource opted in`() {
        val opsOnly = ClientGenerator(testOptions) { ApiModelReader(testOptions).read(listOf(OpsResource::class.java)) }

        val failure = assertThrows<IllegalStateException> { opsOnly.render() }

        failure.message!! shouldContain "@TypeScriptApi"
    }

    /** The lint reads the same model the client is emitted from. */
    @Test
    fun `exposes the contract lint over its own model`() {
        generator().lint().violations() shouldBe emptyList()
    }
}
