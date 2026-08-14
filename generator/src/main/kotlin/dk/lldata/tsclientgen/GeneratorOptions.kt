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
     */
    val scalars: Map<String, String> = DEFAULT_SCALARS,
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

        val DEFAULT_SCALARS: Map<String, String> =
            mapOf(
                "kotlin.String" to "string",
                "kotlin.Char" to "string",
                "kotlin.Int" to "number",
                "kotlin.Long" to "number",
                "kotlin.Short" to "number",
                "kotlin.Byte" to "number",
                "kotlin.Double" to "number",
                "kotlin.Float" to "number",
                "kotlin.Boolean" to "boolean",
                "java.math.BigDecimal" to "number",
                "java.util.UUID" to "string",
                "java.net.URI" to "string",
                "java.time.Instant" to "string",
                "java.time.LocalDate" to "string",
                "java.time.LocalTime" to "string",
                "java.time.LocalDateTime" to "string",
                "java.time.OffsetDateTime" to "string",
                "java.time.ZonedDateTime" to "string",
                "java.time.Duration" to "string",
            )
    }
}
