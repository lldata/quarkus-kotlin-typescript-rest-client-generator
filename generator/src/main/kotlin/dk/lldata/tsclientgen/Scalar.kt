package dk.lldata.tsclientgen

/**
 * How a type that is already a scalar on the wire is spelled in TypeScript.
 *
 * All three forms carry the same JSON. The difference is how much the client can still say about
 * the value — see [ScalarStyle], which picks one for a whole set at a time.
 */
sealed interface Scalar {
    /** The TypeScript type this is, ultimately: `string`, `number`, `boolean`. */
    val spelling: String

    /** What a use site writes. */
    val reference: String

    /** The right-hand side of the alias to declare, or null when there is nothing to declare. */
    val declaration: String?

    /** Written at every use site: `addedAt: string`. Right for `String`, `Int`, `Boolean`. */
    data class Inline(
        override val spelling: String,
    ) : Scalar {
        override val reference get() = spelling
        override val declaration: String? get() = null
    }

    /**
     * Declared once and referred to by name: `export type Instant = string`, then `addedAt: Instant`.
     *
     * This is the treatment a `@JvmInline value class` already gets — `Isbn` is emitted as
     * `export type Isbn = string`, because Jackson unwraps it to a string. A `java.time.Instant` is
     * the same kind of thing: a named scalar. Inlining one and naming the other was an accident of
     * which code path each took, not a decision.
     */
    data class Named(
        val name: String,
        override val spelling: String,
    ) : Scalar {
        override val reference get() = name
        override val declaration get() = spelling
    }

    /**
     * A named alias a bare [spelling] cannot be assigned to, plus a companion object to construct
     * one through.
     *
     * @param pattern the body of a regex the value must match, **unanchored** — the emitter wraps it
     *   as `^(?:…)$`. That anchoring is not cosmetic: Bean Validation's `@Pattern` uses
     *   `Matcher.matches()`, which must match the whole input, while a JavaScript `test()` searches
     *   for a substring. An unanchored port of `\d{3}` accepts `"abc123xyz"` in the client and is
     *   rejected by the server.
     * @param temporal the `Temporal` type this converts to, where one exists. Recorded here so a
     *   conversion module can be emitted from it; nothing in the client itself refers to it.
     */
    data class Branded(
        val name: String,
        override val spelling: String,
        val pattern: String? = null,
        val temporal: String? = null,
    ) : Scalar {
        override val reference get() = name
        override val declaration get() = """$spelling & { readonly __brand: "$name" }"""
    }

    companion object {
        @JvmStatic
        fun inline(spelling: String): Scalar = Inline(spelling)

        @JvmStatic
        fun named(
            name: String,
            spelling: String,
        ): Scalar = Named(name, spelling)

        @JvmStatic
        @JvmOverloads
        fun branded(
            name: String,
            spelling: String,
            pattern: String? = null,
            temporal: String? = null,
        ): Scalar = Branded(name, spelling, pattern, temporal)
    }
}
