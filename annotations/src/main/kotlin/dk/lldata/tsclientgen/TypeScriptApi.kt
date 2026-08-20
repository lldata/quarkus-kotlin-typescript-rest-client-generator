package dk.lldata.tsclientgen

/**
 * This resource is part of the published TypeScript client.
 *
 * The generator emits a call for every endpoint on an annotated resource, and the rules in
 * `ApiContractLint` judge exactly those endpoints — whether request bodies are typed, whether a
 * payload has a flat name, whether an emitted name shadows a global.
 * None of that applies to a resource without this annotation, because nothing generates a client
 * for it.
 *
 * **Opt-in on purpose.** A resource is outside the client until someone says otherwise, so adding
 * one cannot publish it by accident. That makes the annotation the review point for "is this part
 * of the contract the browser calls" — a question the path alone cannot answer, since operational
 * endpoints usually live under the same prefix.
 *
 * Leave it off for anything reached another way or by someone else: an admin surface, a webhook a
 * third party posts to, a test-only harness, an endpoint reached by browser navigation rather than
 * by `fetch`.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class TypeScriptApi
