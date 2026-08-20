package dk.lldata.tsclientgen.lint

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue
import dk.lldata.tsclientgen.GeneratorOptions
import dk.lldata.tsclientgen.ScalarStyle
import dk.lldata.tsclientgen.fixture.BookListResponse
import dk.lldata.tsclientgen.fixture.ClientApi
import dk.lldata.tsclientgen.fixtureModel
import dk.lldata.tsclientgen.model.ApiModelReader
import dk.lldata.tsclientgen.testOptions
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Everything a lint rule is supposed to catch, in one resource nobody would ship. */
@ClientApi
@Path("/api/broken")
private class BrokenResource {
    @POST
    @Path("/untyped")
    fun untypedBody(body: String): BookListResponse = TODO()

    @GET
    @Path("/nested")
    fun nested(): Holder.Nested = TODO()

    @GET
    @Path("/off-naming")
    fun offNaming(): PlainPayload = TODO()

    @GET
    @Path("/drifted-union")
    fun driftedUnion(): DriftedUnionResponse = TODO()

    /**
     * `@JsonValue` governs the body, not the query string — so the client sends `"loud"` and JAX-RS
     * looks for `LOUD`.
     */
    @GET
    @Path("/unconvertible")
    fun unconvertible(
        @QueryParam("mood") mood: Mood,
    ): BookListResponse = TODO()
}

/** A path parameter whose name is a reserved word, which only bites where it is bound. */
@ClientApi
@Path("/api/reserved")
private class ReservedArgumentResource {
    @GET
    @Path("/{delete}")
    fun byName(
        @PathParam("delete") delete: String,
    ): BookListResponse = TODO()
}

/**
 * Ordinary domain nouns that are all globals: a payload, an enum, and a value object.
 *
 * Off the `*Response` naming too, on purpose — the two rules are independent and a name can break
 * both, which is worth seeing reported as two things rather than one.
 */
@ClientApi
@Path("/api/shadowing")
private class ShadowingResource {
    @GET
    @Path("/where")
    fun where(): Location = TODO()
}

private data class Location(
    val document: Document,
    val kind: Event,
)

private data class Document(
    val text: String,
)

private enum class Event {
    ARRIVED,
    LEFT,
}

private enum class Mood(
    @JsonValue val wireValue: String,
) {
    LOUD("loud"),
    QUIET("quiet"),
}

/**
 * Off the client surface: nothing about its TypeScript spelling is anyone's business, but the verb
 * rule is about the HTTP API and still reaches it.
 */
@Path("/api/ops")
private class OffSurfaceResource {
    @DELETE
    @Path("/cache")
    fun dropCache(): Holder.Nested = TODO()
}

private class Holder {
    data class Nested(
        val value: String,
    )
}

private data class PlainPayload(
    val value: String,
)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind", visible = true)
@JsonSubTypes(JsonSubTypes.Type(value = OnlyMappedVariant::class, name = "not-a-kind"))
private sealed interface DriftedUnionResponse {
    val kind: DriftKind
}

private enum class DriftKind {
    MAPPED,
}

private data class OnlyMappedVariant(
    val value: String,
) : DriftedUnionResponse {
    override val kind: DriftKind = DriftKind.MAPPED
}

private data class UnmappedVariant(
    val other: String,
) : DriftedUnionResponse {
    override val kind: DriftKind = DriftKind.MAPPED
}

class ApiContractLintTest {
    /** The fixture surface is what a project that follows the rules looks like. */
    @Test
    fun `a resource surface that follows the rules has nothing to report`() {
        ApiContractLint(fixtureModel(), testOptions).violations().shouldBeEmpty()
    }

    @Test
    fun `check throws with every violation it found`() {
        val failure = assertThrows<IllegalStateException> { brokenLint().check() }

        failure.message!! shouldContain "TYPED_REQUEST_BODY"
        failure.message!! shouldContain "BrokenResource.untypedBody"
    }

    /** A rule a project has not adopted can be filtered rather than exempted case by case. */
    @Test
    fun `check can be narrowed to the rules a project has adopted`() {
        // Nothing wrong with the paths, so narrowing to that rule passes on a resource that breaks
        // several others.
        brokenLint().check(setOf(ApiContractRule.USABLE_PATH_SEGMENTS))

        assertThrows<IllegalStateException> { brokenLint().check(setOf(ApiContractRule.TYPED_REQUEST_BODY)) }
    }

    @Test
    fun `catches a request body the client could only be told is a string`() {
        subjectsFor(ApiContractRule.TYPED_REQUEST_BODY) shouldContainExactly listOf("BrokenResource.untypedBody")
    }

    @Test
    fun `catches a response type with no flat TypeScript name`() {
        subjectsFor(ApiContractRule.TOP_LEVEL_RESPONSE_TYPE) shouldContainExactly listOf("BrokenResource.nested")
    }

    /** Adding a variant without mapping it would silently drop an arm from the client. */
    @Test
    fun `catches a sealed union whose mapping is not exactly its subclasses`() {
        val violation = violations().first { it.rule == ApiContractRule.SEALED_SUBTYPES_COMPLETE }

        violation.subject shouldBe "DriftedUnionResponse"
        violation.message shouldContain "unmapped: UnmappedVariant"
    }

    /** A mapping that merely looks right puts a value in the client that no response carries. */
    @Test
    fun `catches a variant literal the discriminator cannot hold`() {
        val violation = violations().first { it.rule == ApiContractRule.VARIANT_LITERAL_IN_DISCRIMINATOR }

        violation.subject shouldBe "DriftedUnionResponse.OnlyMappedVariant"
        violation.message shouldContain "\"not-a-kind\""
    }

    /**
     * The client sends the `@JsonValue` spelling everywhere, and JAX-RS converts a query parameter
     * with `valueOf`. Without a `fromString` the call type-checks, reads correctly, and 404s.
     */
    @Test
    fun `catches an enum query param JAX-RS cannot convert from what the client sends`() {
        subjectsFor(ApiContractRule.ENUM_QUERY_PARAM_CONVERTIBLE) shouldContainExactly listOf("Mood")
    }

    /** An enum whose wire spelling is its constant name needs nothing, and neither does one with the hook. */
    @Test
    fun `says nothing about an enum param that converts`() {
        ApiContractLint(fixtureModel(), testOptions).violations().none {
            it.rule == ApiContractRule.ENUM_QUERY_PARAM_CONVERTIBLE
        } shouldBe true
    }

    /**
     * The emitter refuses one at a time, which is no way to learn that a whole surface is like
     * this. The lint reports the set, whatever the policy is set to.
     */
    @Test
    fun `reports every endpoint it could not describe, at once`() {
        val untyped =
            ApiContractLint(
                ApiModelReader(testOptions).read(listOf(dk.lldata.tsclientgen.fixture.UntypedResponseResource::class.java)),
                testOptions.copy(untypedPayloads = dk.lldata.tsclientgen.UntypedPolicy.Ignore),
            )

        val reported = untyped.violations().filter { it.rule == ApiContractRule.UNTYPED_PAYLOAD }

        reported.single().subject shouldBe "UntypedResponseResource.untyped"
        reported.single().message shouldContain "jakarta.ws.rs.core.Response"
    }

    /**
     * Every rule reads the client surface and nothing else. `OffSurfaceResource.dropCache` returns
     * a nested type under a name no rule would accept, and is reported by none of them, because no
     * client is emitted for it — the constraint the rules exist to protect never reaches it.
     */
    @Test
    fun `scopes every rule to the client surface`() {
        val violations = brokenLint().violations()

        violations.none { it.subject == "OffSurfaceResource.dropCache" } shouldBe true
    }

    /**
     * `{delete}` becomes the argument the caller passes, and a reserved word cannot be a binding.
     *
     * The member it hangs off is fine — `{ delete: … }` is an ordinary property — so nothing else
     * in this rule sees it, and the emitted file would fail to parse rather than to type-check.
     */
    @Test
    fun `catches a variable segment that cannot be the argument it becomes`() {
        val lint =
            ApiContractLint(
                ApiModelReader(testOptions).read(listOf(ReservedArgumentResource::class.java)),
                testOptions,
            )

        val reported = lint.violations().filter { it.rule == ApiContractRule.USABLE_PATH_SEGMENTS }

        reported.single().subject shouldBe "delete"
        reported.single().message shouldContain "cannot be the name of the argument"
    }

    /**
     * The import wins for the importing file, so the collision is not the client's problem — it is
     * every consumer file's, with a diagnostic naming the same type on both sides.
     */
    @Test
    fun `catches an emitted name that shadows a standard-library global`() {
        val lint =
            ApiContractLint(
                ApiModelReader(brokenOptions).read(listOf(ShadowingResource::class.java)),
                brokenOptions,
            )

        val reported = lint.violations().filter { it.rule == ApiContractRule.SHADOWS_A_GLOBAL }

        reported.map { it.subject } shouldContainExactlyInAnyOrder listOf("Location", "Document", "Event")
        reported.first().message shouldContain "shadows"
    }

    /** A project sure it never imports the DOM can drop the rule, like any other. */
    @Test
    fun `the shadowing rule can be filtered like any other`() {
        val lint =
            ApiContractLint(
                ApiModelReader(brokenOptions).read(listOf(ShadowingResource::class.java)),
                brokenOptions,
            )

        lint.check(ApiContractRule.entries.toSet() - ApiContractRule.SHADOWS_A_GLOBAL)
    }

    /**
     * The shipped scalar table currently avoids every global, which the issue that asked for this
     * rule pointed out is luck: `java.net.URI` is aliased `URI`, and `URL` would have collided.
     * Asserted here so the next scalar added has to be a deliberate choice.
     */
    @Test
    fun `no scalar the generator ships names a global`() {
        for (style in ScalarStyle.entries) {
            val aliases = GeneratorOptions.scalars(style).values.map { it.reference }

            aliases.filter { it in ApiContractLint.TYPESCRIPT_GLOBALS }.shouldBeEmpty()
        }
    }

    private fun brokenLint() =
        ApiContractLint(
            ApiModelReader(testOptions).read(listOf(BrokenResource::class.java, OffSurfaceResource::class.java)),
            brokenOptions,
        )

    private fun violations() = brokenLint().violations()

    private fun subjectsFor(rule: ApiContractRule) = violations().filter { it.rule == rule }.map { it.subject }

    private companion object {
        /** The broken payloads live beside this test, so the lint has to look for them here. */
        val brokenOptions = testOptions.copy(dtoPackages = testOptions.dtoPackages + "dk.lldata.tsclientgen.lint")
    }
}
