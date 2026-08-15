package dk.lldata.tsclientgen.emit

import dk.lldata.tsclientgen.ClientGenerator
import dk.lldata.tsclientgen.GeneratorOptions
import dk.lldata.tsclientgen.fixture.BookDto
import dk.lldata.tsclientgen.fixture.BookListResponse
import dk.lldata.tsclientgen.fixture.BorrowResponse
import dk.lldata.tsclientgen.fixture.ReplicationReport
import dk.lldata.tsclientgen.fixture.ReplicationStrategy
import dk.lldata.tsclientgen.fixture.WideMemberResponse
import dk.lldata.tsclientgen.model.Endpoint
import dk.lldata.tsclientgen.model.Param
import dk.lldata.tsclientgen.model.ParamSource
import dk.lldata.tsclientgen.testOptions
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Shapes built to overrun the print width, because every consumer so far has found a new way to.
 *
 * Each of those was the same mistake — a construct handed to the writer as a format string, where
 * it sees one opaque token and cannot break what it cannot see. The sweep below is an attempt to
 * find the rest of them at once rather than one release at a time, so the count levels off.
 *
 * The endpoints are built rather than compiled, because the interesting dimensions are quantities —
 * path depth, parameter count, name length — and writing forty resources to vary three numbers
 * would test the fixtures more than the emitter.
 */
class EmittedWidthTest {
    /**
     * Every offending line at once, not just the first: a shape that overruns usually overruns in
     * several places, and finding them one test run at a time is the slow way round.
     */
    private fun noneOverlong(source: String) {
        val found = overlong(source)
        if (found.isNotEmpty()) found.joinToString("\n", prefix = "\n") shouldBe ""
    }

    /**
     * A line the writer could have broken and did not.
     *
     * Not simply "longer than 80": some lines cannot be shortened by any layout, and calling those
     * failures would mean the test could only be satisfied by lying. The exemptions below are the
     * ones Prettier itself makes — checked by running it over the same shapes — so the property
     * this asserts is the useful one: the output is already what a formatter would produce.
     */
    private fun overlong(source: String): List<String> =
        source
            .lines()
            .withIndex()
            .filter { (_, line) -> line.length > PRINT_WIDTH }
            .filterNot { (_, line) -> isUnbreakable(line) }
            .map { (index, line) -> "line ${index + 1}, ${line.length} cols: ${line.trim()}" }

    private fun isUnbreakable(line: String) =
        hasUnbreakableToken(line) || isMemberDeclaration(line) || isFullyBroken(line) || isNamedImport(line)

    /**
     * A known gap, exempted here and asserted on its own below.
     *
     * TypeScriptPoet emits the named-import list as one pre-joined string, so the writer sees no
     * boundary to break at — the same mistake this sweep exists to find, one layer down. Tracked
     * as lldata/typescriptpoet#15. Prettier expands the list all-or-nothing, which is not
     * something this project can work around from the outside: the statement is Poet's to write.
     */
    private fun isNamedImport(line: String): Boolean = line.startsWith("import {")

    /** One token already wider than the budget: no layout helps, and Prettier does not try. */
    private fun hasUnbreakableToken(line: String): Boolean {
        val indent = line.takeWhile { it == ' ' }.length
        val widest = line.trim().split(' ').maxOf { it.length }
        return indent + widest > PRINT_WIDTH
    }

    /** `addedAt: Instant | null;` — one member of an interface or an object type. */
    private fun isMemberDeclaration(line: String): Boolean = MEMBER.matches(line)

    /**
     * A line the writer has already broken as far as the grammar allows.
     *
     * `export function someVeryLongFactoryName(` is the case in practice: the arguments are on
     * their own lines below, and the head is a keyword and a name with nothing between them to
     * break at. Prettier leaves these exactly as they are.
     */
    private fun isFullyBroken(line: String): Boolean = line.trimEnd().endsWith("(")

    private fun emitted(
        endpoints: List<Endpoint>,
        options: GeneratorOptions = testOptions,
    ) = TypeScriptClientEmitter(options).emit(endpoints)

    /** Long enough to matter, and made of a repeated syllable so a failure is readable. */
    private fun name(
        length: Int,
        of: String = "veryLongSegment",
    ) = of.repeat(length / of.length + 1).take(length)

    private fun get(
        path: String,
        params: List<Param> = emptyList(),
        response: KType = typeOf<BookDto>(),
    ) = Endpoint(
        resource = "SweptResource",
        function = "swept",
        verb = "GET",
        path = path,
        params = params,
        responseType = response,
        unwrappedFrom = null,
        inTypeScriptApi = true,
    )

    private fun query(
        wireName: String,
        type: KType = typeOf<String>(),
        default: String? = null,
    ) = Param(wireName, wireName, ParamSource.QUERY, type, type.isMarkedNullable, default)

    private fun pathParam(wireName: String) = Param(wireName, wireName, ParamSource.PATH, typeOf<String>(), false)

    /** A path that is mostly literal segments: the factory names grow with the depth. */
    @Test
    fun `a deep path of long segments`() {
        for (depth in 1..8) {
            for (segment in listOf(4, 16, 40)) {
                val path = (1..depth).joinToString("/", "/api/") { name(segment, "segment$it-") }
                noneOverlong(emitted(listOf(get(path))))
            }
        }
    }

    /** Every variable segment adds a method, an interpolation and a factory name. */
    @Test
    fun `a deep path of variable segments`() {
        for (depth in 1..6) {
            val names = (1..depth).map { name(20, "variableSegment$it-") }
            val path = names.joinToString("/", "/api/") { "{$it}" }
            noneOverlong(emitted(listOf(get(path, names.map(::pathParam)))))
        }
    }

    /** The args object grows with the count, and each member may carry a doc comment. */
    @Test
    fun `many query parameters, named at length, some defaulted`() {
        for (count in 1..12) {
            val params =
                (1..count).map { index ->
                    val defaulted = index % 3 == 0
                    query(
                        name(18, "queryParameter$index-"),
                        typeOf<String?>(),
                        default = if (defaulted) name(30, "default$index-") else null,
                    )
                }
            noneOverlong(emitted(listOf(get("/api/things", params))))
        }
    }

    /** One parameter so wide the args object cannot fit however few there are. */
    @Test
    fun `a single enormously named query parameter`() {
        val params = listOf(query(name(70, "enormouslyNamedQueryParameter-")))
        noneOverlong(emitted(listOf(get("/api/things", params))))
    }

    /**
     * The one shape the writer still cannot break — pinned rather than merely exempted.
     *
     * A generated client a few directories down from its runtime is enough to overrun: the fixed
     * part of the statement is already 52 columns. Prettier expands the list one name per line,
     * so the output here is not a fixed point. When lldata/typescriptpoet#15 lands this test
     * fails, which is the reminder to drop the exemption in [isNamedImport].
     */
    @Test
    fun `a long runtime import path is left on one line`() {
        val far = "../".repeat(6) + "client-runtime.js"
        val statement =
            emitted(listOf(get("/api/things")), testOptions.copy(runtimeImport = far))
                .lines()
                .single { it.startsWith("import {") }

        statement shouldBe "import { ApiConfig, RequestOptions, apiRequest } from \"$far\";"
        statement.length shouldBeGreaterThan PRINT_WIDTH
    }

    /** The union of every scalar alias, plus the branded companions and their patterns. */
    @Test
    fun `every scalar branded at once`() {
        val branded = testOptions.copy(scalars = GeneratorOptions.VALUE_OBJECT_SCALARS)
        val params = listOf(query("at", typeOf<java.time.Instant>()), query("on", typeOf<java.time.LocalDate>()))
        noneOverlong(emitted(listOf(get("/api/things", params, typeOf<BookListResponse>())), branded))
    }

    /** A sealed response: the alias is a union of every variant's name. */
    @Test
    fun `a discriminated union response`() {
        noneOverlong(emitted(listOf(get("/api/things", response = typeOf<BorrowResponse>()))))
    }

    /** Nested generics: the type is built up from the inside and never gets a line of its own. */
    @Test
    fun `deeply nested collection types`() {
        val nested = typeOf<Map<String, List<Map<String, List<BookDto>>>>>()
        noneOverlong(emitted(listOf(get("/api/things", response = nested))))
    }

    /** A literal union past the width: the alias has to break between the variants. */
    @Test
    fun `an enum of many long literals`() {
        val params = listOf(query("strategy", typeOf<ReplicationStrategy>()))
        noneOverlong(emitted(listOf(get("/api/things", params))))
    }

    /** Members whose own types are wide, which is where a member declaration stops being one line. */
    @Test
    fun `an interface of wide members`() {
        noneOverlong(emitted(listOf(get("/api/things", response = typeOf<WideMemberResponse>()))))
    }

    /** Long variant names stretch both the alias and each variant's discriminator literal. */
    @Test
    fun `a discriminated union of long variant names`() {
        noneOverlong(emitted(listOf(get("/api/things", response = typeOf<ReplicationReport>()))))
    }

    /**
     * `GET_OR_POST_ONLY` is a rule a project may filter out, and the lint says so — "the generator
     * itself emits any verb". That has to be true, and `delete` is where it stops being obvious:
     * it is a reserved word, so it cannot be a declaration's name, while `{ delete: … }` is an
     * ordinary property. Poet validates the name it is handed, so the arrow is left anonymous.
     */
    @Test
    fun `emits the verbs the contract rule merely discourages`() {
        val path = "/api/things"
        val endpoints =
            listOf("PUT", "PATCH", "DELETE").map { verb ->
                get(path).copy(verb = verb, function = verb.lowercase())
            }

        val emitted = emitted(endpoints)

        emitted shouldContain "delete: (options?: RequestOptions): Promise<BookDto> =>"
        emitted shouldContain "apiRequest<BookDto>({ method: \"DELETE\", path }, config, options)"
    }

    /** One node carrying every verb, so the object literal grows without the path doing so. */
    @Test
    fun `every verb on one node`() {
        val path = "/api/" + name(30, "aRatherLongResourceSegment-")
        val endpoints =
            listOf("GET", "POST", "PUT", "PATCH", "DELETE").map { verb ->
                get(path, listOf(query(name(20, "queryParameter-"), typeOf<String?>()))).copy(verb = verb, function = verb.lowercase())
            }
        noneOverlong(emitted(endpoints))
    }

    /** The root path is written as a literal at every call site, so a long one is repeated. */
    @Test
    fun `a long root path`() {
        val root = "/" + (1..4).joinToString("/") { name(18, "rootSegment$it-") }
        noneOverlong(emitted(listOf(get("$root/things")), testOptions.copy(rootPath = root)))
    }

    /**
     * The runtime is hand-written and copied out verbatim, so no emitter is keeping it in line.
     *
     * It ships in every consumer's tree next to the generated files and is formatted with them, so
     * a line over the width here is a diff in somebody's repository the first time they run
     * Prettier — the one file in the output where that could happen quietly.
     */
    @Test
    fun `the shipped runtime`() {
        val runtime = ClientGenerator::class.java.getResource(RUNTIME)!!.readText()

        noneOverlong(runtime)
    }

    /** The Temporal module is a second emitter, and inherits none of the first one's fixes. */
    @Test
    fun `the temporal module at width`() {
        val options = testOptions.copy(scalars = GeneratorOptions.VALUE_OBJECT_SCALARS)
        val emitted =
            TemporalModuleEmitter(options).emit(
                options.scalars.values.toList(),
                clientImport = "../".repeat(6) + "generated/client.js",
                temporalImport = "@js-temporal/polyfill",
            )
        noneOverlong(emitted.orEmpty())
    }

    /** Everything at once, which is where the interactions live. */
    @Test
    fun `all of it together`() {
        val names = (1..3).map { name(18, "variable$it-") }
        val path = "/api/" + (1..3).joinToString("/") { name(22, "literalSegment$it-") } + "/" + names.joinToString("/") { "{$it}" }
        val params =
            names.map(::pathParam) +
                (1..6).map { query(name(16, "queryParam$it-"), typeOf<String?>(), default = name(24, "aLongDefault$it-")) }

        val everything =
            emitted(
                listOf(get(path, params, typeOf<BookListResponse>())),
                testOptions.copy(scalars = GeneratorOptions.VALUE_OBJECT_SCALARS, runtimeImport = "../".repeat(6) + "client-runtime.js"),
            )

        noneOverlong(everything)
    }

    private companion object {
        /** TypeScriptPoet's, and Prettier's default. */
        const val PRINT_WIDTH = 80

        /** The hand-written runtime, as it is copied into a consumer's tree. */
        const val RUNTIME = "/dk/lldata/tsclientgen/client-runtime.ts"

        /**
         * `  name?: Type | null;` — a declaration with nowhere to break.
         *
         * The name may be quoted: a wire name that is not an identifier is emitted as
         * `"page-size"?: number`, which is still a member and still unbreakable.
         */
        val MEMBER = Regex("""^\s*(readonly )?("[^"]*"|'[^']*'|[\w$]+)\??: .*[;,]?$""")
    }
}
