# The bookshelf example

A working consumer of the generator: shelves of books that can be searched, added, borrowed,
returned and removed. Small enough to read in one sitting, and it uses every feature the generator
has a rule for.

```bash
(cd example/web && npm ci && npm run build)   # compiles the page; tsc is the only dependency
./gradlew :example:backend:quarkusDev
```

Then <http://localhost:8088>. The data is in memory, so restarting resets it.

## What to read, in order

Every file is linked rather than quoted, so nothing here can go stale against the code.

**1. The vocabulary** —
[Domain.kt](backend/src/main/kotlin/dk/lldata/tsclientgen/example/Domain.kt) and
[Payloads.kt](backend/src/main/kotlin/dk/lldata/tsclientgen/example/Payloads.kt). One of each thing
the generator treats specially: two flavours of `@JsonValue` enum, value classes, a
`@JsonInclude(NON_NULL)` response, a request with Kotlin defaults, and a sealed hierarchy with its
`@JsonSubTypes` mapping.

The split is worth noticing. `Book` and `Shelf` are the domain *and* the wire — there is no
`BookDto` shadowing a `Book`, and `@TypeScriptValueObject` is how they keep their own names instead
of being renamed `*Response`. Payloads.kt holds only the shapes that exist because a call has to
answer with more than a book; `GET /api/shelves` needs none of them and answers with `List<Shelf>`.

**2. The rules** —
[Catalogue.kt](backend/src/main/kotlin/dk/lldata/tsclientgen/example/Catalogue.kt). What a search
matches, what sorting means, whether a copy can be lent, what counts as a duplicate. None of it
mentions HTTP: a refusal is a `CatalogueFailure`, and
[CatalogueFailureMapper.kt](backend/src/main/kotlin/dk/lldata/tsclientgen/example/CatalogueFailureMapper.kt)
is the one place that decides an unknown shelf is a 404.

**3. The resources** —
[BooksResource.kt](backend/src/main/kotlin/dk/lldata/tsclientgen/example/BooksResource.kt) and
[ShelvesResource.kt](backend/src/main/kotlin/dk/lldata/tsclientgen/example/ShelvesResource.kt). Nearly
every method is one line: bind the request, hand it to the catalogue. What stays is what is
genuinely HTTP — a `@BeanParam` holder and the `toSearch()` seam that ends it, a `@DefaultValue`
query parameter whose Kotlin name is not its wire name, a node that is both an endpoint and a
namespace, a `return` segment, a bodyless `RestResponse<Void>` where the status is the whole answer,
and ambient parameters (a cookie, an injected `UriInfo`) that never reach the client.
[OpsResource.kt](backend/src/main/kotlin/dk/lldata/tsclientgen/example/OpsResource.kt) carries no
`@TypeScriptApi` and is therefore absent from the client — that is the opt-in working.

**4. What came out** — [client.ts](web/src/generated/client.ts). Read it next to the Kotlin: every
declaration in it is an answer to one in step 1, and the call tree at the bottom is the paths from
step 3. [client-runtime.ts](web/src/generated/client-runtime.ts) is the transport, written by the
generator and replaceable.

**5. What it is like to use** — [app.ts](web/src/app.ts). No URL is spelled in it, no response is
cast, and the sealed response narrows in a `switch` that stops compiling if Kotlin gains a variant.

**6. How it is wired** —
[ExampleClient.kt](backend/src/test/kotlin/dk/lldata/tsclientgen/example/codegen/ExampleClient.kt),
[GenerateApiClient.kt](backend/src/test/kotlin/dk/lldata/tsclientgen/example/codegen/GenerateApiClient.kt)
and
[GeneratedClientDriftTest.kt](backend/src/test/kotlin/dk/lldata/tsclientgen/example/codegen/GeneratedClientDriftTest.kt).
Three short files, and the whole integration.

## Trying the loop

Change something in the Kotlin — rename a field, make a nullable one non-null, add a variant to
`BorrowResponse` — and then:

```bash
./gradlew :example:backend:test              # the drift test fails: the committed client is stale
./gradlew :example:backend:generateApiClient # writes example/web/src/generated/
(cd example/web && npm run typecheck)        # now the page fails to compile, at the line that cares
```

That is the entire point of the thing: the break lands at build time, in the file that has to
change, rather than as `undefined` in a browser.

`./gradlew :example:backend:printApiModel` prints the API as the generator reads it — the fastest
way to see what a resource is failing to declare.

## Two wrinkles worth knowing

**`@JsonValue` does not govern query parameters.** It is a Jackson annotation, and JAX-RS converts
query parameters with `valueOf`. `Condition` therefore carries a static `fromString`, without which
`?condition=worn` — what the generated client sends — is a 404 while `?condition=WORN` works. The
`ENUM_QUERY_PARAM_CONVERTIBLE` lint rule exists because of this example.

**Value classes and dev-mode hot reload.** Editing a file with `quarkusDev` running can leave
jackson-module-kotlin holding a value class from the previous classloader, which surfaces as a
`LinkageError` on the next request that deserializes one, or as a field quietly missing from a
response. Restart the app; it is a Quarkus dev-mode artifact and not something the generated client
can see.
