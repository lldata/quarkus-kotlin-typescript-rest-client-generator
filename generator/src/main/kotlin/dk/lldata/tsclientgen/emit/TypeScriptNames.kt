package dk.lldata.tsclientgen.emit

/**
 * Names that come off the wire and have to survive being written into TypeScript source.
 *
 * A query parameter is named by the server, and `page-size` is an ordinary thing for a server to
 * call one. It is not an identifier, though, so it cannot be written the way a member usually is —
 * `{ page-size: string }` parses as a subtraction and fails. TypeScript has an answer for exactly
 * this: quote the member and subscript the access. The wire name is preserved either way, which is
 * the point — camel-casing it would be a silent rename of somebody else's parameter.
 */
internal object TypeScriptNames {
    /** What can be written bare. Deliberately conservative: ASCII, though the language allows more. */
    private val IDENTIFIER = Regex("[a-zA-Z_$][a-zA-Z0-9_$]*")

    fun isIdentifier(name: String) = name.matches(IDENTIFIER)

    /** `page-size` becomes `"page-size"`; `limit` is left alone. */
    fun member(name: String) = if (isIdentifier(name)) name else "\"$name\""

    /**
     * `args.limit`, but `args["page-size"]`.
     *
     * [receiver] carries its own optional chaining — `args` or `args?` — because whether the args
     * object itself may be omitted is the caller's business, not the member name's. The two forms
     * differ in where the `?.` goes: `args?.limit` against `args?.["page-size"]`.
     */
    fun access(
        receiver: String,
        name: String,
    ) = when {
        isIdentifier(name) -> "$receiver.$name"
        receiver.endsWith("?") -> "$receiver.[${member(name)}]"
        else -> "$receiver[${member(name)}]"
    }
}
