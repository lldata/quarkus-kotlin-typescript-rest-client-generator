package dk.lldata.tsclientgen

/**
 * Everything about the emitted client that is a project's decision rather than the generator's.
 *
 * Only [dtoPackages] has no defensible default: without it the generator cannot tell one of your
 * payload types from a `java.util.Locale` that happens to be reachable, and it would try to declare
 * the JDK.
 */
data class GeneratorOptions(
    /**
     * Package prefixes the payload types live under. A reachable type outside them is either a
     * known scalar (see [scalars]) or a gap in the contract, and the generator says so rather than
     * emitting a name that resolves to nothing.
     */
    val dtoPackages: List<String>,
    /**
     * The prefix every endpoint path shares. It becomes the root node's path, so no emitted node
     * carries it, and `createApi()` returns the members below it.
     */
    val rootPath: String = "/api",
    /**
     * Where the emitted file imports `apiRequest`, `ApiConfig` and `RequestOptions` from, spelled
     * exactly as the import statement should read.
     *
     * Write the shipped runtime next to the client with
     * [dk.lldata.tsclientgen.ClientGenerator.writeRuntimeTo], or point this at your own module when
     * the transport carries project policy — auth, retries, a proxy hop. Nothing else in the
     * generated file knows how a request is made.
     */
    val runtimeImport: String = "./client-runtime",
    /** The comment at the top of the emitted file. Say how to regenerate it. */
    val header: String = DEFAULT_HEADER,
    /**
     * Name for a module-level client built with no config, or null for none.
     *
     * Convenient in a browser, where the session cookie is the credential and there is one user per
     * process. Set it to null for a server-side client: a token held at module level would be
     * shared by every request the process serves, and `createApi({ token })` is then the only way in.
     */
    val defaultInstanceName: String? = "api",
    /**
     * Headers the caller never sets, because the browser attaches them or the edge does. A header
     * param outside this set is a gap the emitter refuses on, since a client that compiles and
     * silently omits a required header is worse than one that will not build.
     */
    val ambientHeaders: Set<String> = DEFAULT_AMBIENT_HEADERS,
    /**
     * Return types that wrap the payload rather than being it. `RestResponse<BookResponse>` is a
     * `BookResponse` on the wire plus a status the client reads from the response itself.
     */
    val wrapperTypes: Set<String> = DEFAULT_WRAPPER_TYPES,
    /**
     * Kotlin and JDK types that already have a TypeScript spelling, by qualified name.
     *
     * The `java.time` entries assume ISO-8601 strings, which is what Jackson writes with
     * `write-dates-as-timestamps` off — the Quarkus default. Add your own for anything else a
     * serializer flattens to a scalar: a `BigDecimal` written as a string, a `UUID`, a
     * project-wide wrapper with a custom serializer.
     *
     * A [Scalar.Named] keeps the name the Kotlin type had, which is the same treatment a
     * `@JvmInline value class` gets — see [Scalar].
     */
    val scalars: Map<String, Scalar> = DEFAULT_SCALARS,
) {
    init {
        require(dtoPackages.isNotEmpty()) {
            "dtoPackages is empty, so no type would be recognised as a payload and the client would declare nothing."
        }
        require(rootPath.startsWith("/")) { "rootPath must start with '/', was '$rootPath'." }
        require(runtimeImport.isNotBlank()) { "runtimeImport is what the emitted import statement says; it cannot be blank." }
    }

    companion object {
        const val DEFAULT_HEADER: String = "Generated from the JAX-RS resources. Do not edit: fix the generator instead."

        /**
         * `Cookie` is a forbidden header name — `fetch` refuses to send one set from JavaScript —
         * and the forwarding pair is written by a proxy or CDN. A client argument for any of them
         * would be unusable.
         */
        val DEFAULT_AMBIENT_HEADERS: Set<String> = setOf("cookie", "x-forwarded-for", "x-real-ip")

        /** Matched by qualified name, so the generator needs none of these on its own classpath. */
        val DEFAULT_WRAPPER_TYPES: Set<String> =
            setOf(
                "org.jboss.resteasy.reactive.RestResponse",
                "jakarta.ws.rs.core.GenericEntity",
                "io.smallrye.mutiny.Uni",
                "java.util.concurrent.CompletionStage",
                "java.util.concurrent.CompletableFuture",
            )

        /**
         * The primitives are inline, because `string` is the whole truth about a `String`.
         *
         * The rest are named: a timestamp, an identifier and a duration are all "a string" only in
         * the sense that everything is. Naming them costs nothing — they stay structurally
         * assignable to `string` — and it is the only thing standing between a client that says
         * `addedAt: Instant` and one that says `addedAt: string`, which is the same information a
         * `format: date-time` in an OpenAPI document carries.
         */
        val DEFAULT_SCALARS: Map<String, Scalar> =
            mapOf(
                "kotlin.String" to Scalar.inline("string"),
                "kotlin.Char" to Scalar.inline("string"),
                "kotlin.Int" to Scalar.inline("number"),
                "kotlin.Long" to Scalar.inline("number"),
                "kotlin.Short" to Scalar.inline("number"),
                "kotlin.Byte" to Scalar.inline("number"),
                "kotlin.Double" to Scalar.inline("number"),
                "kotlin.Float" to Scalar.inline("number"),
                "kotlin.Boolean" to Scalar.inline("boolean"),
                // A JSON number, and the precision a BigDecimal exists for is already gone by the
                // time it is one. Named, so a reader can see where that happened.
                "java.math.BigDecimal" to Scalar.named("BigDecimal", "number"),
                "java.util.UUID" to Scalar.named("UUID", "string"),
                "java.net.URI" to Scalar.named("URI", "string"),
                "java.time.Instant" to Scalar.named("Instant", "string"),
                "java.time.LocalDate" to Scalar.named("LocalDate", "string"),
                "java.time.LocalTime" to Scalar.named("LocalTime", "string"),
                "java.time.LocalDateTime" to Scalar.named("LocalDateTime", "string"),
                "java.time.OffsetDateTime" to Scalar.named("OffsetDateTime", "string"),
                "java.time.ZonedDateTime" to Scalar.named("ZonedDateTime", "string"),
                "java.time.Duration" to Scalar.named("Duration", "string"),
            )

        /**
         * Every scalar inlined, so a timestamp is spelled `string` at each use site and nothing is
         * declared for it.
         *
         * The other defensible position, and one line to adopt:
         * `GeneratorOptions(dtoPackages = …, scalars = GeneratorOptions.INLINE_SCALARS)`. It reads
         * as less noise and less to learn; the cost is that the client can no longer tell a
         * timestamp from a title, which is exactly what an OpenAPI `format: date-time` is for.
         *
         * Neither is wrong. Mix them per type if the whole-set answer fits badly —
         * `DEFAULT_SCALARS + ("java.time.Duration" to Scalar.inline("string"))`.
         */
        val INLINE_SCALARS: Map<String, Scalar> = DEFAULT_SCALARS.mapValues { (_, scalar) -> Scalar.inline(scalar.spelling) }
    }
}
