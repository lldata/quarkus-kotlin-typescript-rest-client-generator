package dk.lldata.tsclientgen.emit

import dk.lldata.tsclientgen.GeneratorOptions
import dk.lldata.tsclientgen.Scalar
import dk.lldata.tsclientgen.UntypedPolicy
import dk.lldata.tsclientgen.fixture.ApiKeyedResource
import dk.lldata.tsclientgen.fixture.IgnoredResource
import dk.lldata.tsclientgen.fixture.LocaleResource
import dk.lldata.tsclientgen.fixture.NamingStrategyResource
import dk.lldata.tsclientgen.fixture.RenamedResource
import dk.lldata.tsclientgen.fixture.UntypedResponseResource
import dk.lldata.tsclientgen.fixtureModel
import dk.lldata.tsclientgen.testOptions
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TypeScriptClientEmitterTest {
    private val model = fixtureModel()
    private val emitter = TypeScriptClientEmitter(testOptions)

    private fun endpoint(id: String) = model.endpoints.first { it.id() == id }

    private fun emit(vararg ids: String) = emitter.emit(ids.map { endpoint(it) })

    private val whole by lazy { emitter.emit(model.clientSurface) }

    /**
     * The tree mirrors the path: a literal segment is a property, a variable one a method named
     * after the variable, and the verb sits at the leaf.
     */
    @Test
    fun `emits the path as a tree of nodes`() {
        val emitted = emit("BooksResource.detail")

        emitted shouldContain "export function createApi(config: ApiConfig = {}) {"
        emitted shouldContain "shelf: (shelf: string) =>"
        emitted shouldContain "isbn: (isbn: string) =>"
        emitted shouldContain "`\${path}/\${encodeURIComponent(String(isbn))}`"
        emitted shouldContain "method: \"GET\""
    }

    /**
     * Seven paths on a real surface are both an endpoint and a namespace. The verb at the leaf is
     * what lets `POST /shelf/{s}/books` and `books/{isbn}` coexist without a rule for the clash.
     */
    @Test
    fun `a node can be both an endpoint and a namespace`() {
        val emitted = emit("BooksResource.add", "BooksResource.detail")

        emitted shouldContain "post: ("
        emitted shouldContain "isbn: (isbn: string) =>"
    }

    /**
     * Every verb JAX-RS ships an annotation for, which is the library's whole policy on verbs:
     * what the platform can declare, the generator emits. Restricting the set is a decision for
     * the project consuming this, not for the generator.
     */
    @Test
    fun `emits every verb JAX-RS can declare`() {
        val emitted =
            emit(
                "DocumentsResource.replace",
                "DocumentsResource.amend",
                "DocumentsResource.remove",
                "DocumentsResource.exists",
                "DocumentsResource.allowed",
            )

        for (verb in listOf("PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")) {
            emitted shouldContain "${verb.lowercase()}: ("
            emitted shouldContain "method: \"$verb\""
        }
    }

    /**
     * `delete` is a reserved word, so it can be a property and never a declaration — the arrow is
     * built anonymously for exactly that reason, and a named function spec would be refused by the
     * writer before the file was written.
     */
    @Test
    fun `emits a reserved word as a verb member`() {
        emit("DocumentsResource.remove") shouldContain "delete: (options?: RequestOptions): Promise<void> =>"
    }

    /**
     * `options` names both the verb member and the per-call parameter every leaf takes. The
     * parameter is the one in scope inside the arrow, so the call still forwards the caller's
     * options rather than the node's own member.
     */
    @Test
    fun `keeps the options verb distinct from the options parameter`() {
        emit("DocumentsResource.allowed") shouldContain
            "options: (options?: RequestOptions): Promise<void> =>"
    }

    /** A reserved word is legal as a property, so the client keeps the name the wire uses. */
    @Test
    fun `keeps a reserved word as a member name`() {
        whole shouldContain "get return()"
    }

    /**
     * The import statement is the only thing the generated file knows about transport.
     *
     * The two shapes carry `type`, so the line survives `verbatimModuleSyntax` and a runtime that
     * strips types rather than compiling them. `apiRequest` is a function and must not.
     */
    @Test
    fun `imports the transport from the configured module, with the types marked as types`() {
        val emitted = emit("BooksResource.detail")

        emitted shouldContain
            """
            |import {
            |  apiRequest,
            |  type ApiConfig,
            |  type RequestOptions,
            |} from "./client-runtime";
            """.trimMargin()
        emitted shouldContain "Generated. Do not edit."
    }

    @Test
    fun `emits a module-level instance only when one is asked for`() {
        whole shouldContain "export const api: ReturnType<typeof createApi> = createApi();"

        val serverSide = TypeScriptClientEmitter(testOptions.copy(defaultInstanceName = null)).emit(model.clientSurface)
        serverSide shouldNotContain "export const api"
        serverSide shouldContain "export function createApi"
    }

    /** `@BeanParam` is a container; on the wire its fields are ordinary query params. */
    @Test
    fun `flattens a bean param into query arguments`() {
        val emitted = emit("BooksResource.list")

        emitted shouldContain "q?: string"
        emitted shouldContain "sort?: SortField"
        emitted shouldContain "limit?: number"
        emitted shouldContain "q: args?.q"
        // The wire's name, not Kotlin's: `limitRaw` is a server-side detail.
        emitted shouldNotContain "limitRaw"
    }

    /**
     * `page-size` is an ordinary thing for a server to call a parameter and not an identifier, so
     * it cannot be written bare — `{ page-size: number }` parses as a subtraction. Quoting the
     * member and subscripting the access keeps the wire name, which is the only correct answer:
     * camel-casing it would silently rename somebody else's parameter.
     */
    @Test
    fun `quotes a query parameter whose wire name is not an identifier`() {
        val emitted = emit("BooksResource.list")

        emitted shouldContain "\"page-size\"?: number"
        emitted shouldContain "\"page-size\": args?.[\"page-size\"]"
        emitted shouldNotContain "pageSize"
    }

    /**
     * A type that only ever appears in a query string still has to be declared: the call signature
     * names it, and a name nothing declares is a file that does not compile.
     */
    @Test
    fun `declares a type reached only through a query parameter`() {
        val emitted = emit("BooksResource.list")

        emitted shouldContain "export type SortField = \"TITLE\" | \"AUTHOR\";"
    }

    /**
     * Optionality alone reads as "the server copes"; the literal says which answer you get. The
     * comment is the whole reason the args object is an anonymous type rather than an interface.
     */
    @Test
    fun `says what omitting a defaulted query param means`() {
        val emitted = emit("BooksResource.list")

        emitted shouldContain "Server default: `20`."
        emitted shouldContain "limit?: number;"
    }

    /**
     * `ScalarStyle.ValueObject`: the alias becomes a brand, and the companion is how one is made.
     * A type and a value may share a name in TypeScript, which is the closest the language gets to
     * a Kotlin companion object.
     */
    @Test
    fun `emits a branded scalar with a companion to construct it through`() {
        val emitted = TypeScriptClientEmitter(testOptions.copy(scalars = GeneratorOptions.VALUE_OBJECT_SCALARS)).emit(model.clientSurface)

        emitted shouldContain """export type Instant = string & { readonly __brand: "Instant" };"""
        emitted shouldContain "export const Instant"
        emitted shouldContain "from: (raw: string): Instant =>"
        // Anchored, because Bean Validation matches the whole input and `test` does not.
        emitted shouldContain "/^(?:"
        emitted shouldContain ")$/u.test("
        emitted shouldContain "is: (raw: string): raw is Instant =>"
    }

    /**
     * A regex literal is one token and this one is 80 characters, so the line overruns whatever the
     * writer does. Breaking the argument is all there is, and it is what a formatter would do — so a
     * project that runs Prettier over its generated client gets a no-op instead of a reformat that
     * then reads as drift.
     */
    @Test
    fun `breaks the guard's argument, where a formatter would`() {
        val emitted = TypeScriptClientEmitter(testOptions.copy(scalars = GeneratorOptions.VALUE_OBJECT_SCALARS)).emit(model.clientSurface)

        emitted shouldContain ")$/u.test(\n      raw,\n    ),"
    }

    /** No pattern, no guard: one that always returned true would read as a check and be none. */
    @Test
    fun `brands without validating where the format is unknown`() {
        val told =
            testOptions.copy(scalars = testOptions.scalars + ("java.time.Instant" to Scalar.branded("Instant", "string")))

        val emitted = TypeScriptClientEmitter(told).emit(model.clientSurface)

        emitted shouldContain "from: (raw: string): Instant => raw as Instant"
        emitted shouldNotContain "raw is Instant"
    }

    /** The default is unchanged: a plain alias, so no call site has to construct anything. */
    @Test
    fun `names without branding by default`() {
        whole shouldContain "export type Instant = string;"
        whole shouldNotContain "__brand"
    }

    /** Jackson writes the scalar, so the client compares against a string, not an object. */
    @Test
    fun `declares enums and value classes as the scalars they are on the wire`() {
        whole shouldContain "export type Condition = \"new\" | \"used\";"
        whole shouldContain "export type Isbn = string;"
        whole shouldContain "export type Copies = number;"
    }

    /** The base is its variants. Emitting the base's own members describes none of them. */
    @Test
    fun `declares a sealed response as a union of its variants`() {
        val emitted = emit("BooksResource.borrow")

        emitted shouldContain "export type BorrowResponse = BorrowAccepted | BorrowRefused;"
        emitted shouldContain "outcome: \"accepted\";"
        emitted shouldContain "outcome: \"refused\";"
        emitted shouldContain "Promise<BorrowResponse>"
    }

    /** A default decides what is written, not whether it is written. */
    @Test
    fun `marks response fields optional only where Jackson can drop them`() {
        val emitted = emit("BooksResource.detail")

        emitted shouldContain "note?: string | null;"
        emitted shouldContain "alwaysThere: number;"
    }

    /** A request reads the other way: whatever Kotlin can default, the caller can omit. */
    @Test
    fun `marks request fields optional where the constructor defaults them`() {
        val emitted = emit("BooksResource.add")

        emitted shouldContain "title: string;"
        emitted shouldContain "condition?: Condition;"
        emitted shouldContain "tags?: string[];"
    }

    /** `RestResponse<Void>` has no TypeScript spelling; `Promise<void>` does. */
    @Test
    fun `emits a bodyless endpoint as a void promise`() {
        emit("BooksResource.remove") shouldContain "Promise<void>"
    }

    /** The session cookie and an injected context are never client arguments. */
    @Test
    fun `treats cookies and injected context as ambient`() {
        val emitted = emit("BooksResource.remove")

        emitted shouldContain "post: (options?: RequestOptions)"
        emitted shouldNotContain "session"
        emitted shouldNotContain "uriInfo"
    }

    /**
     * A header the caller would have to set itself is a gap, not an ambient one: emitting the
     * endpoint anyway would produce a function that compiles and silently omits it.
     */
    @Test
    fun `refuses a param shape it cannot express`() {
        val keyed = fixtureModel(ApiKeyedResource::class.java)

        val failure = assertThrows<IllegalStateException> { emitter.emit(keyed.endpoints) }

        failure.message!! shouldContain "X-Api-Key"
        failure.message!! shouldContain "cannot express yet"
    }

    /**
     * A name nothing declares is a file that does not compile. This used to be emitted silently:
     * `locale: Locale`, with no `Locale` anywhere in the output.
     */
    @Test
    fun `refuses a payload type it has no spelling for`() {
        val unmapped = fixtureModel(LocaleResource::class.java)

        val failure = assertThrows<IllegalStateException> { emitter.emit(unmapped.endpoints) }

        failure.message!! shouldContain "java.util.Locale"
        failure.message!! shouldContain "dtoPackages"
        failure.message!! shouldContain "scalars"
    }

    /** Saying so is one line, and then it is spelled like any other named scalar. */
    @Test
    fun `takes a spelling for a type outside the payload packages`() {
        val unmapped = fixtureModel(LocaleResource::class.java)
        val told = testOptions.copy(scalars = testOptions.scalars + ("java.util.Locale" to Scalar.named("Locale", "string")))

        val emitted = TypeScriptClientEmitter(told).emit(unmapped.endpoints)

        emitted shouldContain "export type Locale = string;"
        emitted shouldContain "locale: Locale;"
    }

    /**
     * `@JsonProperty` renames a field, and emitting the Kotlin name gives a client that compiles,
     * reads `undefined` and fails nowhere. Read wherever Kotlin put the annotation: on the
     * constructor parameter for an untargeted one, on the backing field for `@field:`.
     */
    @Test
    fun `emits the name the wire uses, not the one Kotlin uses`() {
        val emitted = TypeScriptClientEmitter(testOptions).emit(fixtureModel(RenamedResource::class.java).endpoints)

        emitted shouldContain "display_name: string;"
        emitted shouldContain "shelf_count: number;"
        emitted shouldContain "plain: string;"
        emitted shouldNotContain "displayName"
        emitted shouldNotContain "shelfCount"
    }

    /** A field Jackson never writes is a field the client must not claim to have. */
    @Test
    fun `leaves out a property that is not on the wire`() {
        val emitted = TypeScriptClientEmitter(testOptions).emit(fixtureModel(RenamedResource::class.java).endpoints)

        emitted shouldNotContain "internalOnly"
    }

    /** The rename is the wire's business; optionality is still Kotlin's. */
    @Test
    fun `keys optionality off the Kotlin name while emitting the wire name`() {
        val emitted = TypeScriptClientEmitter(testOptions).emit(fixtureModel(RenamedResource::class.java).endpoints)

        emitted shouldContain "shelf_count?: number;"
    }

    /**
     * A strategy renames everything at once and lives in jackson-databind, which the generator does
     * not depend on — so it can be detected and not run. Emitting the Kotlin names anyway would
     * rename the whole payload behind the client's back.
     */
    @Test
    fun `refuses a naming strategy it cannot apply`() {
        val naming = fixtureModel(NamingStrategyResource::class.java)

        val failure = assertThrows<IllegalStateException> { emitter.emit(naming.endpoints) }

        failure.message!! shouldContain "@JsonNaming"
        failure.message!! shouldContain "@JsonProperty"
    }

    /**
     * The order of the two rules matters and is easy to lose: ignored properties are filtered out
     * *before* their types are resolved, so a field that is not on the wire cannot fail the build
     * for having a type the generator has no spelling for.
     */
    @Test
    fun `does not resolve the type of a property it is not emitting`() {
        val emitted = TypeScriptClientEmitter(testOptions).emit(fixtureModel(IgnoredResource::class.java).endpoints)

        emitted shouldContain "title: string;"
        emitted shouldNotContain "locale"
    }

    /**
     * `jakarta.ws.rs.core.Response` is what a plain Jersey or Jakarta EE resource returns, and it
     * says nothing about its body. The generic refusal sent whoever hit it down two wrong paths —
     * it is not a payload type to add to `dtoPackages`, and not a scalar to spell — so it names the
     * real problem and what to do instead.
     */
    @Test
    fun `says what is wrong with a return type that erases its payload`() {
        val untyped = fixtureModel(UntypedResponseResource::class.java)

        val failure = assertThrows<IllegalStateException> { emitter.emit(untyped.endpoints) }

        failure.message!! shouldContain "does not say what it carries"
        failure.message!! shouldContain "RestResponse<T>"
        failure.message!! shouldNotContain "dtoPackages"
    }

    /**
     * A codebase that predates the generator can have dozens of these, so refusing is a setting
     * rather than a rule. `unknown` is the only honest spelling once it does not refuse — anything
     * narrower would be a guess the caller would then trust.
     */
    @Test
    fun `emits unknown and says so when told to warn about an untyped payload`() {
        val warnings = mutableListOf<String>()
        val warning = testOptions.copy(untypedPayloads = UntypedPolicy.Warn, onWarning = { warnings += it })

        val emitted = TypeScriptClientEmitter(warning).emit(fixtureModel(UntypedResponseResource::class.java).endpoints)

        emitted shouldContain "Promise<unknown>"
        warnings.single() shouldContain "jakarta.ws.rs.core.Response"
    }

    @Test
    fun `emits unknown silently when told to ignore it`() {
        val warnings = mutableListOf<String>()
        val ignoring = testOptions.copy(untypedPayloads = UntypedPolicy.Ignore, onWarning = { warnings += it })

        val emitted = TypeScriptClientEmitter(ignoring).emit(fixtureModel(UntypedResponseResource::class.java).endpoints)

        emitted shouldContain "Promise<unknown>"
        warnings.shouldBeEmpty()
    }

    /** A payload that reaches itself used to recurse until the stack ran out. */
    @Test
    fun `emits a recursive payload`() {
        emit("CommentsResource.tree") shouldContain "replies: CommentResponse[];"
    }

    /**
     * Every leaf closes over the config, which is the only way a token reaches a request — passed
     * as its own argument rather than merged into `options`, so a per-call token cannot exist to be
     * silently overridden.
     */
    @Test
    fun `every call carries the client config`() {
        val emitted = emit("BooksResource.detail")

        // Layout is the writer's business — it breaks the argument list on width — so assert the
        // arguments rather than the whitespace between them.
        emitted shouldContain "apiRequest<BookDetailResponse>("
        emitted.collapseWhitespace() shouldContain "config, options,"
        emitted shouldNotContain "token: config.token"
    }

    private fun String.collapseWhitespace() = replace(Regex("\\s+"), " ")
}
