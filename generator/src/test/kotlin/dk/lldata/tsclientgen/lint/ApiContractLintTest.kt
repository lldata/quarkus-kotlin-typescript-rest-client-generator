package dk.lldata.tsclientgen.lint

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue
import dk.lldata.tsclientgen.TypeScriptApi
import dk.lldata.tsclientgen.fixture.BookListResponse
import dk.lldata.tsclientgen.fixtureModel
import dk.lldata.tsclientgen.model.ApiModelReader
import dk.lldata.tsclientgen.testOptions
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Everything a lint rule is supposed to catch, in one resource nobody would ship. */
@TypeScriptApi
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
        // five others.
        brokenLint().check(setOf(ApiContractRule.USABLE_PATH_SEGMENTS))

        assertThrows<IllegalStateException> { brokenLint().check(setOf(ApiContractRule.GET_OR_POST_ONLY)) }
    }

    @Test
    fun `catches a request body the client could only be told is a string`() {
        subjectsFor(ApiContractRule.TYPED_REQUEST_BODY) shouldContainExactly listOf("BrokenResource.untypedBody")
    }

    @Test
    fun `catches a response type with no flat TypeScript name`() {
        subjectsFor(ApiContractRule.TOP_LEVEL_RESPONSE_TYPE) shouldContainExactly listOf("BrokenResource.nested")
    }

    /** `*Response`, or shared vocabulary that says so. A value object is exempt by construction. */
    @Test
    fun `catches a payload named neither Response nor a value object`() {
        subjectsFor(ApiContractRule.RESPONSE_NAMING) shouldContainExactly listOf("Nested", "PlainPayload")
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
     * The verb rule reads every endpoint; the naming rules read only the client surface. An
     * off-surface resource returning a nested, badly named type is caught by the first and by
     * neither of the others.
     */
    @Test
    fun `scopes each rule to the surface it answers to`() {
        val violations = brokenLint().violations()

        violations.filter { it.rule == ApiContractRule.GET_OR_POST_ONLY }.map { it.subject } shouldContainExactly
            listOf("DELETE /api/ops/cache")
        violations.none { it.subject == "OffSurfaceResource.dropCache" } shouldBe true
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
