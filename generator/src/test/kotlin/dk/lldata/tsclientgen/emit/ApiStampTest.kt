package dk.lldata.tsclientgen.emit

import dk.lldata.tsclientgen.ApiDigest
import dk.lldata.tsclientgen.GeneratorOptions
import dk.lldata.tsclientgen.StampOptions
import dk.lldata.tsclientgen.fixtureModel
import dk.lldata.tsclientgen.testOptions
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The stamp is what a vendored copy has instead of the drift test: a value it can compare without
 * cloning the backend. So the two things worth holding down are that it reports incompatibility
 * rather than churn, and that the same contract always hashes the same way.
 */
class ApiStampTest {
    private val model = fixtureModel()
    private val surface = model.clientSurface

    private fun digestOf(options: GeneratorOptions = testOptions) = ContractDigest(options).of(surface)

    private fun emit(stamp: StampOptions?) = TypeScriptClientEmitter(testOptions.copy(stamp = stamp)).emit(surface)

    @Test
    fun `emits nothing when no stamp is configured`() {
        val emitted = emit(null)

        emitted shouldNotContain "API_STAMP"
        emitted shouldContain "export function createApi(config: ApiConfig = {}) {"
    }

    /** Typed `string` rather than inferred, so comparing one with a value is not a literal comparison. */
    @Test
    fun `exports the composed stamp as a const`() {
        val emitted = emit(StampOptions(value = { "9.9.9.${it.short}" }))

        emitted shouldContain "export const API_STAMP: string = \"9.9.9.${digestOf().short}\";"
        // Nothing is sent, so there is no header name to export and no config to merge into.
        emitted shouldNotContain "API_STAMP_HEADER"
        emitted shouldContain "export function createApi(config: ApiConfig = {}) {"
    }

    /**
     * The one place a header can be added once and reach every call: every node below closes over
     * the config the root hands it.
     */
    @Test
    fun `sends the stamp from createApi when a header is named`() {
        val emitted = emit(StampOptions(value = { it.short }, header = "x-api-stamp"))

        emitted shouldContain "export const API_STAMP_HEADER: string = \"x-api-stamp\";"
        emitted shouldContain
            """
            |export function createApi(baseConfig: ApiConfig = {}) {
            |  const config: ApiConfig = {
            |    ...baseConfig,
            |    headers: { "x-api-stamp": API_STAMP, ...baseConfig.headers },
            |  };
            """.trimMargin()
    }

    /** A header the server names is not always an identifier, and quoting it is the only way in. */
    @Test
    fun `quotes a header name that is not an identifier`() {
        val emitted = emit(StampOptions(value = { it.short }, header = "X-Api-Stamp"))

        emitted shouldContain "\"X-Api-Stamp\": API_STAMP"
    }

    @Test
    fun `refuses a blank header, which would emit a nameless one`() {
        assertThrows<IllegalArgumentException> { StampOptions(value = { it.short }, header = " ") }
    }

    @Test
    fun `hashes the same contract to the same value`() {
        digestOf().modelHash shouldBe digestOf().modelHash
    }

    /**
     * Reflection does not promise an order and neither does a resource author. If the hash moved
     * when a method was moved up a file, it would report churn on every reordering and mean nothing.
     */
    @Test
    fun `does not depend on the order the endpoints arrive in`() {
        val reversed = ContractDigest(testOptions).of(surface.reversed())

        reversed.modelHash shouldBe digestOf().modelHash
    }

    /**
     * The two options that change the emitted file without changing what a consumer writes code
     * against. `runtimeImport` especially: a vendored copy commonly rewrites that line on the way
     * in, and a stamp that moved on it would report every such copy as a different client.
     */
    @Test
    fun `does not move on the header comment or the runtime import`() {
        val reworded = digestOf(testOptions.copy(header = "Something else entirely."))
        val revendored = digestOf(testOptions.copy(runtimeImport = "../vendor/client-runtime.ts"))

        reworded.modelHash shouldBe digestOf().modelHash
        revendored.modelHash shouldBe digestOf().modelHash
    }

    /** What the stamp exists to catch: the client can no longer describe the API it was built for. */
    @Test
    fun `moves when an endpoint leaves the surface`() {
        val fewer = ContractDigest(testOptions).of(surface.drop(1))

        fewer.modelHash shouldNotBe digestOf().modelHash
    }

    /** A scalar style is a call-site change, so it is part of what a consumer depends on. */
    @Test
    fun `moves when a scalar is spelled differently`() {
        val branded = digestOf(testOptions.copy(scalars = GeneratorOptions.VALUE_OBJECT_SCALARS))

        branded.modelHash shouldNotBe digestOf().modelHash
    }

    /**
     * The projection is the answer to "why did the hash move", so it has to be readable and to
     * carry the things the hash claims to cover.
     */
    @Test
    fun `describes the contract it hashed`() {
        val contract = digestOf().contract

        contract shouldContain "root /api"
        contract shouldContain "GET /api/shelf/{shelf}/books"
        contract shouldContain "  query page-size?: number"
        contract shouldContain "  -> BookListResponse"
        contract shouldContain "type Isbn = string"
        contract shouldNotContain "client-runtime"
    }

    @Test
    fun `hashes the projection it reports`() {
        digestOf() shouldBe ApiDigest.of(digestOf().contract)
    }
}
