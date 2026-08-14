package dk.lldata.tsclientgen

/**
 * How a type that is already a scalar on the wire is spelled in TypeScript.
 *
 * All three forms carry the same JSON. The difference is how much the client can still say about
 * the value: `addedAt: string` and `addedAt: Instant` are the same type to the compiler, and only
 * one of them tells a reader that it is a timestamp rather than a title.
 *
 * There is no right answer here, so it is a per-type choice rather than a mode — see
 * [GeneratorOptions.scalars], and [GeneratorOptions.INLINE_SCALARS] for the other whole-set default.
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
     *
     * Still structurally a `string`, so nothing is forced to cast and no existing call site breaks.
     * It buys documentation, not enforcement. [Branded] buys enforcement.
     */
    data class Named(
        val name: String,
        override val spelling: String,
    ) : Scalar {
        override val reference get() = name
        override val declaration get() = spelling
    }

    /**
     * A named alias a bare [spelling] cannot be assigned to:
     * `export type Instant = string & { readonly __brand: "Instant" }`.
     *
     * The strongest of the three, and the only one with a cost. Reading is unaffected — a branded
     * string still goes into `new Date(…)` and compares with `<` — and the transport already casts
     * the parsed body, so a response needs no assertion. *Constructing* one does: a request field
     * takes `"2026-01-01" as Instant` rather than `"2026-01-01"`.
     *
     * Worth it where mixing two identifiers up is a real bug that a plain alias would not catch;
     * not worth it where the client mostly reads.
     */
    data class Branded(
        val name: String,
        override val spelling: String,
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
        fun branded(
            name: String,
            spelling: String,
        ): Scalar = Branded(name, spelling)
    }
}
