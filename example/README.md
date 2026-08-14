# The bookshelf example

A working consumer of the generator: shelves of books that can be searched, added, borrowed,
returned and removed. Small enough to read in one sitting, and it uses every feature the generator
has a rule for.

```bash
(cd example/web && npm ci && npm run build)   # compiles the page; tsc is the only dependency
./gradlew :example:backend:quarkusDev
```

Then <http://localhost:8088>. The data is in memory, so restarting resets it.

The page assumes a reasonably modern browser. The floor is set by the shipped transport, which uses
`AbortSignal.any` and `AbortSignal.timeout` to combine a caller's abort with a per-call timeout, and
by the page's own `replaceChildren`; nothing here is polyfilled or transpiled, because the point of
the example is that what runs is what was generated. Check anything you are unsure of on
[caniuse](https://caniuse.com).

Worth knowing before you reach for `Temporal` in a client of your own: it is not yet everywhere —
[caniuse.com/temporal](https://caniuse.com/temporal) had it at 69% of global usage in July 2026,
with the gap almost entirely older Safari and iOS, where every browser is WebKit and a user cannot
route around it. That is why converting a date is a decision for the consuming project rather than
something the generated client does for you.

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

## The control group: what OpenAPI says about the same resources

`quarkus-smallrye-openapi` is on the example, so the same annotated classes also produce a document
at <http://localhost:8088/q/openapi> and a UI at <http://localhost:8088/q/swagger-ui>. It is here as
evidence rather than as documentation: the generator's whole premise is that a second description of
the API disagrees with the first in ways that still compile, and this is where you can check that
claim instead of taking it.

Four of the example's types, as each side describes them:

| | OpenAPI says | The generator says | On the wire |
| --- | --- | --- | --- |
| `Condition` | `enum: [NEW, USED, WORN]` | `"new" \| "used" \| "worn"` | `"worn"` — `@JsonValue` |
| `BorrowResponse` | `{ outcome }` — the sealed base's own member, no variants | `BorrowAccepted \| BorrowRefused` | one of the two variants, in full |
| `note` on `BookDetailResponse` | `required`, `string \| null` | `note?: string \| null` | absent — the class is `@JsonInclude(NON_NULL)` |
| `Instant` | `string`, `format: date-time` | `Instant` (`= string`) | an ISO-8601 string |

The first three are the failure this project exists to prevent: a client generated from that
document would compare `condition === "NEW"` against a payload that says `"worn"`, would see nothing
on a `BorrowResponse` but `outcome`, and would null-check a field that is not there. Each one
compiles.

The fourth is the closest OpenAPI comes to winning, and it is worth saying out loud. `format:
date-time` is a claim about the *shape* of the string; `export type Instant = string` is only a
name, structurally identical to `string`, so nothing stops a title being assigned to it. The
generator names the scalar rather than inlining it — the same treatment `Isbn` gets, since both are
"a scalar with a name in Kotlin" — but naming buys documentation, not enforcement.
`Scalar.branded("Instant", "string")` buys the enforcement, at the cost of a cast wherever a client
*constructs* one. All three positions are one line in `GeneratorOptions.scalars`; see
[Scalar.kt](../generator/src/main/kotlin/dk/lldata/tsclientgen/Scalar.kt).

Two more things the document gets right and the table does not show: `AddBookRequest` marks
`condition` and `tags` optional, matching the Kotlin defaults, and it describes the query parameters
including `limit`'s `default: 50`. On the query parameters it even has the enum spelling the *other*
way round — `?condition=NEW`, which is what JAX-RS converts with `valueOf` — which is the same trap
from the other side, and why `Condition.fromString` exists.

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
