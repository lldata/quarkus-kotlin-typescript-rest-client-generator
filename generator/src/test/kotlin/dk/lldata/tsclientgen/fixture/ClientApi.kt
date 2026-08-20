package dk.lldata.tsclientgen.fixture

/**
 * The fixture package's own marker, named in `testOptions.includeAnnotations`.
 *
 * The generator ships no annotation, so a fixture that looked like one would be testing a shape no
 * consumer can have. This is what a project actually writes.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ClientApi

/** Keeps one endpoint off the client while its resource stays on. Mirrors `@Operation(hidden = true)`. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Hidden(
    val value: Boolean = true,
)
