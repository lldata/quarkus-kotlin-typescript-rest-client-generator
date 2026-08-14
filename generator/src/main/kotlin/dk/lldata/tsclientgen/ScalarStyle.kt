package dk.lldata.tsclientgen

/**
 * How much identity a scalar keeps once it is in the client.
 *
 * A timestamp, an ISBN and a title are all `string` on the wire, and the wire is the only thing the
 * generator can be certain about. What it does with that is a project's decision, not the
 * generator's — hence a policy rather than a default nobody chose.
 *
 * Pick one with [GeneratorOptions.scalars], and override per type where the whole-set answer fits
 * badly:
 *
 * ```kotlin
 * GeneratorOptions(
 *     dtoPackages = listOf("com.example.api"),
 *     scalars = GeneratorOptions.scalars(ScalarStyle.ValueObject) +
 *         ("java.time.Duration" to Scalar.inline("string")),
 * )
 * ```
 */
enum class ScalarStyle {
    /**
     * `addedAt: string`. Nothing is declared and nothing is claimed.
     *
     * The least to learn, and the least said: a client holding one of these cannot tell a timestamp
     * from a title.
     */
    Inline,

    /**
     * `export type Instant = string`, then `addedAt: Instant`.
     *
     * The default, and free: an alias is structurally the same type, so no call site changes and
     * nothing has to be constructed differently. It buys documentation — roughly what an OpenAPI
     * `format: date-time` carries — and no enforcement.
     */
    Named,

    /**
     * `export type Instant = string & { readonly __brand: "Instant" }`, plus a companion:
     * `Instant.from(raw)` to construct one and `Instant.is(raw)` to test one.
     *
     * Reading is unaffected — a brand is an intersection, so every string method, comparison and
     * interpolation still works. The cost lands where a client *constructs* a value: a request
     * field takes `Instant.from(x)` rather than a bare string. That is the point. The string it
     * refuses is usually one that came from a text input and was never checked.
     *
     * Where the format is known — the `java.time` family, `UUID` — the companion validates. Where
     * it is not, `from` brands without checking and says so.
     */
    ValueObject,
}
