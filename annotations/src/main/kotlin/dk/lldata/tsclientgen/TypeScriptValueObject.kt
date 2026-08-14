package dk.lldata.tsclientgen

/**
 * A value object shared across calls, emitted once in the TypeScript client.
 *
 * Most payload types exist to be one endpoint's response, and the naming rule in
 * [dk.lldata.tsclientgen.lint.ApiContractLint] expects them to say so by ending in `Response`.
 * These are different: they are the same concept in several places — a public profile, a money
 * amount, a message — so `*Response` would be the wrong name.
 *
 * Marking one says: this type is part of the shared vocabulary between Kotlin and TypeScript, it
 * keeps its own name, and it is exempt from the response-naming rule by construction rather than by
 * an exemption list somebody maintains.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class TypeScriptValueObject
