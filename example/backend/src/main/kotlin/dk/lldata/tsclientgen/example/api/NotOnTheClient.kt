package dk.lldata.tsclientgen.example.api

/**
 * This resource is not part of the TypeScript client, though it is part of the HTTP API.
 *
 * The generator ships no annotation of its own: this one belongs to the example, and the example
 * names it in `GeneratorOptions.excludeAnnotations`. Any project writes its own three lines and
 * calls it whatever fits its vocabulary.
 *
 * It is the class-level half of the exclusion. The endpoint-level half is
 * `@Operation(hidden = true)`, which is MicroProfile's and hides an endpoint from the OpenAPI
 * document as well — see [BooksResource.reindex].
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class NotOnTheClient
