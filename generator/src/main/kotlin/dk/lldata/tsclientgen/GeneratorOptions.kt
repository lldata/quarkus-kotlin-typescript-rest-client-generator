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
     * Provenance the emitted client carries — see [StampOptions]. Null, and nothing is emitted.
     *
     * Off by default because it has a cost worth choosing deliberately: with a stamp, every change
     * to the API shape moves a line in the committed client. That is the whole point, and it is
     * still a project's decision rather than something an upgrade should start doing to its diffs.
     */
    val stamp: StampOptions? = null,
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
    /**
     * What to do about an endpoint the generator cannot describe — see [UntypedPolicy]. Refusing by
     * default, because a generated client is worth having only while its types are true.
     */
    val untypedPayloads: UntypedPolicy = UntypedPolicy.Fail,
    /**
     * Where [UntypedPolicy.Warn] reports to. Standard error by default, which is where a Gradle
     * `JavaExec` puts it in front of whoever ran the task.
     */
    val onWarning: (String) -> Unit = { System.err.println("tsclientgen: $it") },
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

        // Unanchored bodies: the emitter wraps each as `^(?:...)$`. See Scalar.Branded.pattern for
        // why that anchoring is load-bearing rather than tidy.
        private const val UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        private const val DATE_PATTERN = """\d{4}-\d{2}-\d{2}"""
        private const val TIME_PATTERN = """\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?"""
        private const val DATE_TIME_PATTERN = DATE_PATTERN + "T" + TIME_PATTERN
        private const val INSTANT_PATTERN = """$DATE_TIME_PATTERN(?:Z|[+-]\d{2}:\d{2})"""
        private const val ZONED_PATTERN = """$INSTANT_PATTERN(?:\[[^\]]+\])?"""
        private const val DURATION_PATTERN = """-?P(?:\d+D)?(?:T(?:\d+H)?(?:\d+M)?(?:\d+(?:\.\d+)?S)?)?"""

        /**
         * One entry per type the generator knows is a scalar on the wire, with everything the
         * three [ScalarStyle]s need projected out of it.
         *
         * @param alias null for the primitives: `string` is the whole truth about a `String`, and
         *   there is no identity to keep. Everything else has a name worth keeping.
         */
        private data class KnownScalar(
            val qualifiedName: String,
            val alias: String?,
            val spelling: String,
            val pattern: String? = null,
            val temporal: String? = null,
        )

        /**
         * The patterns are ISO-8601 as Jackson writes it with `write-dates-as-timestamps` off, and
         * they are deliberately loose about seconds and fractions — a validating constructor is
         * there to catch a title pasted into a date field, not to re-implement a date parser.
         */
        private val KNOWN_SCALARS =
            listOf(
                KnownScalar("kotlin.String", null, "string"),
                KnownScalar("kotlin.Char", null, "string"),
                KnownScalar("kotlin.Int", null, "number"),
                KnownScalar("kotlin.Long", null, "number"),
                KnownScalar("kotlin.Short", null, "number"),
                KnownScalar("kotlin.Byte", null, "number"),
                KnownScalar("kotlin.Double", null, "number"),
                KnownScalar("kotlin.Float", null, "number"),
                KnownScalar("kotlin.Boolean", null, "boolean"),
                // A JSON number, and the precision a BigDecimal exists for is already gone by the
                // time it is one. Named, so a reader can see where that happened.
                KnownScalar("java.math.BigDecimal", "BigDecimal", "number"),
                KnownScalar("java.util.UUID", "UUID", "string", pattern = UUID_PATTERN),
                // No pattern: a URI is close enough to "any string" that checking one would refuse
                // things the server accepts.
                KnownScalar("java.net.URI", "URI", "string"),
                KnownScalar("java.time.Instant", "Instant", "string", INSTANT_PATTERN, "Temporal.Instant"),
                KnownScalar("java.time.LocalDate", "LocalDate", "string", DATE_PATTERN, "Temporal.PlainDate"),
                KnownScalar("java.time.LocalTime", "LocalTime", "string", TIME_PATTERN, "Temporal.PlainTime"),
                KnownScalar("java.time.LocalDateTime", "LocalDateTime", "string", DATE_TIME_PATTERN, "Temporal.PlainDateTime"),
                // No Temporal equivalent carries a bare offset; ZonedDateTime is the closest and is
                // not the same thing, so this one converts to nothing rather than to something near.
                KnownScalar("java.time.OffsetDateTime", "OffsetDateTime", "string", INSTANT_PATTERN),
                KnownScalar("java.time.ZonedDateTime", "ZonedDateTime", "string", ZONED_PATTERN, "Temporal.ZonedDateTime"),
                KnownScalar("java.time.Duration", "Duration", "string", DURATION_PATTERN, "Temporal.Duration"),
                // kotlinx-datetime, whose serializers write the same ISO-8601, so the same aliases.
                // `kotlin.time.Instant` is where `kotlinx.datetime.Instant` moved, and both spell one.
                KnownScalar("kotlinx.datetime.Instant", "Instant", "string", INSTANT_PATTERN, "Temporal.Instant"),
                KnownScalar("kotlin.time.Instant", "Instant", "string", INSTANT_PATTERN, "Temporal.Instant"),
                KnownScalar("kotlinx.datetime.LocalDate", "LocalDate", "string", DATE_PATTERN, "Temporal.PlainDate"),
                KnownScalar("kotlinx.datetime.LocalTime", "LocalTime", "string", TIME_PATTERN, "Temporal.PlainTime"),
                KnownScalar("kotlinx.datetime.LocalDateTime", "LocalDateTime", "string", DATE_TIME_PATTERN, "Temporal.PlainDateTime"),
                // Deliberately absent: `kotlin.time.Duration`. kotlinx-serialization writes it
                // ISO-8601, Jackson sees a value class and writes a number, and the generator
                // cannot tell which is serialising. An unmapped type refuses loudly, which is the
                // right outcome for a type whose wire form depends on that.
            )

        /**
         * The known scalars, spelled according to [style]. Add to it or override entries in it —
         * it is an ordinary map.
         */
        @JvmStatic
        fun scalars(style: ScalarStyle): Map<String, Scalar> =
            KNOWN_SCALARS.associate { known ->
                val alias = known.alias
                known.qualifiedName to
                    when {
                        alias == null || style == ScalarStyle.Inline -> Scalar.inline(known.spelling)
                        style == ScalarStyle.Named -> Scalar.named(alias, known.spelling, known.temporal)
                        else -> Scalar.branded(alias, known.spelling, known.pattern, known.temporal)
                    }
            }

        /**
         * [ScalarStyle.Named]: a name, no enforcement, no call site changed.
         *
         * The default because it is the only one of the three that costs a consumer nothing to
         * adopt. [ScalarStyle.ValueObject] is better and is one line away; it is not a default,
         * because an upgrade should not silently make a project's request bodies stop compiling.
         */
        val DEFAULT_SCALARS: Map<String, Scalar> = scalars(ScalarStyle.Named)

        val INLINE_SCALARS: Map<String, Scalar> = scalars(ScalarStyle.Inline)

        /** [ScalarStyle.ValueObject]: branded, with a companion to construct one through. */
        val VALUE_OBJECT_SCALARS: Map<String, Scalar> = scalars(ScalarStyle.ValueObject)
    }
}
