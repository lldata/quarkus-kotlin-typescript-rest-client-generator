package dk.lldata.tsclientgen

/**
 * Provenance in the emitted client: a string the client treats as opaque and the server that
 * minted it can decompose.
 *
 * ```ts
 * export const API_STAMP = "1.0.0-alpha4.9f3c1a2e";
 * export const API_STAMP_HEADER = "x-api-stamp";
 * ```
 *
 * A `const` rather than only a comment in the header, because a comment can be diffed and not
 * sent. What it buys is a consumer that cannot run the generator — a vendored copy, a second
 * service — being able to tell it is stale somewhere other than at a failing call: its CI compares
 * `API_STAMP` against what the server reports, and every request carries the value when [header]
 * names one.
 *
 * Nothing here refuses anything, on either side. Two stamps differing does not mean the client is
 * broken: most backend changes are additive, and a client generated before one usually still works.
 * Answering "is this compatible" needs a compatibility model rather than a hash comparison, so this
 * reports and leaves the judgement to whoever reads it.
 */
data class StampOptions(
    /**
     * What the stamp says. The generator supplies the digest; the composition is the project's —
     * see [ApiDigest] for why the generator does not compose one itself.
     *
     * ```kotlin
     * StampOptions({ "${System.getProperty("myproject.version")}.${it.short}" }, header = "x-api-stamp")
     * StampOptions({ "${gitRev()}.${it.short}" })
     * StampOptions({ it.modelHash })
     * ```
     */
    val value: (ApiDigest) -> String,
    /**
     * The request header every call from `createApi` carries, or null to export the constants
     * without sending anything.
     *
     * Null by default, and no name is suggested: a default would put a header this library
     * invented on the wire the moment anyone turned a stamp on, and make it a de-facto standard
     * that is not this library's to set. Naming it is how sending is switched on.
     *
     * A resource that then reads the stamp with `@HeaderParam` trips the refusal in
     * [GeneratorOptions.ambientHeaders] — as it should, since the client sets this header and the
     * caller never does. Add the name to [GeneratorOptions.ambientHeaders] and the endpoint emits.
     */
    val header: String? = null,
) {
    init {
        require(header == null || header.isNotBlank()) {
            "stamp header is the name of a request header; it cannot be blank. Use null for a stamp that is not sent."
        }
    }
}
