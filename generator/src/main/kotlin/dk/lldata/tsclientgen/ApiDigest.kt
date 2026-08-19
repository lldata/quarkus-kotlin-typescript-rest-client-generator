package dk.lldata.tsclientgen

import java.security.MessageDigest

/**
 * What the generator can say about the shape of the client it is emitting, for a project that
 * wants to stamp it — see [GeneratorOptions.stamp].
 *
 * The generator deliberately says nothing about *its own* version here. It cannot read one without
 * a resource this library would then have to mandate, and the emitting project already knows which
 * generator it depends on: its build file declares it. Folding that in, or a git revision, or
 * anything else, is [StampOptions.value]'s job.
 *
 * That is worth doing rather than stamping [modelHash] alone. An emitter change can move the
 * emitted file without moving the contract — a layout fix, a new import form — and a hash over the
 * contract cannot see it, so a vendored copy would be stale with a stamp that still matched.
 *
 * @param contract the canonical projection [modelHash] is over, and the thing to diff when the
 *   hash moves and it is not obvious why. Also the way in for a project that wants a different
 *   hash entirely: it is plain text, so hash it with whatever, over whatever subset.
 * @param modelHash SHA-256 of [contract], lowercase hex.
 */
data class ApiDigest(
    val contract: String,
    val modelHash: String,
) {
    /** The first 8 hex characters — enough to tell two clients apart by eye, and short enough to read. */
    val short: String get() = modelHash.take(8)

    internal companion object {
        internal fun of(contract: String): ApiDigest =
            ApiDigest(
                contract,
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(contract.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) },
            )
    }
}
