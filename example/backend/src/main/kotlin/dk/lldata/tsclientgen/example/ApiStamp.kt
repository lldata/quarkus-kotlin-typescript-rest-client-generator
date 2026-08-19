package dk.lldata.tsclientgen.example

/**
 * The stamp this backend was built at, and the header a generated client sends its own in.
 *
 * The value is written by `./gradlew :example:backend:generateApiClient`, from the same run that
 * writes the client — so the two carry one value rather than two derivations of one. The generator
 * emits no code for this end: reading the file and deciding what a mismatch is worth are the
 * application's, and this object is the whole of it.
 */
object ApiStamp {
    /**
     * Named here rather than in the codegen configuration because `src/test` can see `src/main` and
     * not the other way round — and because the header is the application's, not the generator's.
     * `ExampleClient` reads it from here, so there is one spelling and the ends cannot drift.
     */
    const val HEADER: String = "x-api-stamp"

    private const val RESOURCE = "/api-stamp.txt"

    /** What a current client carries. */
    val current: String =
        ApiStamp::class.java
            .getResourceAsStream(RESOURCE)
            ?.bufferedReader()
            ?.use { it.readText().trim() }
            ?: error("$RESOURCE is missing. Run `./gradlew :example:backend:generateApiClient`.")

    /**
     * Why [sent] is worth saying something about, or null when it is not.
     *
     * A stamp that differs is not a broken client. Most backend changes are additive, and a client
     * generated before one usually still works — so this reports and never refuses. Answering
     * whether a given pair is *compatible* needs a compatibility model, which a hash comparison is
     * not, and refusing on one would break callers that were fine.
     */
    fun mismatch(sent: String?): String? =
        when (sent) {
            // Not a generated client at all — curl, a health check, the OpenAPI UI. The header is
            // absent rather than wrong, and there is nothing to say about it.
            null -> null
            current -> null
            else ->
                "client was generated against API stamp $sent, this backend is $current. " +
                    "Regenerate it: the two describe different APIs, though the calls it makes may still work."
        }
}
