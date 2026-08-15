package dk.lldata.tsclientgen

/**
 * What to do about an endpoint whose payload type says nothing — a `jakarta.ws.rs.core.Response`,
 * which holds a body without declaring it, or a bare `Any`.
 *
 * There is no good answer the generator can give for one of these. `Promise<unknown>` is honest and
 * nearly useless; refusing is honest and stops the build. Which of those a project wants depends on
 * whether the signatures can be changed, so it is a setting rather than a rule.
 *
 * The typical shape of the problem is a codebase that predates the generator: plain JAX-RS returns
 * `Response` idiomatically, so a Jersey or Jakarta EE surface can have dozens of them. Start on
 * [Warn], read the list, fix them, then turn [Fail] on so no new one appears.
 */
enum class UntypedPolicy {
    /**
     * Emit `unknown` and say nothing.
     *
     * For a surface where the untyped endpoints are known, accepted, and not worth hearing about on
     * every build. The contract lint still reports them, so this hides them from the generator and
     * not from anyone who asks.
     */
    Ignore,

    /**
     * Emit `unknown` and report each one through [GeneratorOptions.onWarning].
     *
     * The adoption setting: the client generates, the caller is told `unknown` rather than
     * something plausible and wrong, and the build says what it could not describe.
     */
    Warn,

    /**
     * Refuse, naming the endpoint and what to do instead. The default.
     *
     * A generated client's value is that its types are true. An endpoint the generator cannot
     * describe is one it should not be quietly guessing about, so on a surface built for it this is
     * the setting that keeps it that way.
     */
    Fail,
}
