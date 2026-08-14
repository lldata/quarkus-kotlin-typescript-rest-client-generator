# quarkus-kotlin-typescript-rest-client-generator

Generates a TypeScript client from annotated Quarkus/JAX-RS Kotlin resources, by reading the
compiled classes rather than an OpenAPI document.

```ts
const detail = await api.shelves.shelf("attic").books.isbn("978-0262510875").get();
//    ^? BookDetailResponse
```

…from an annotated resource method, with no OpenAPI document in between. The two sides of that
example, in full and always current, are
[BooksResource.kt](example/backend/src/main/kotlin/dk/lldata/tsclientgen/example/BooksResource.kt)
and the [client.ts](example/web/src/generated/client.ts) generated from it.

## Why read the classes

An OpenAPI document is a second description of the API, and the interesting failures are the ones
where the two descriptions disagree in a way that still compiles. The compiled classes are the only
place where the annotations, the resolved generic types and Kotlin's nullability all agree, so that
is what this reads.

What that buys, concretely:

| Kotlin | TypeScript | Why not the obvious thing |
| --- | --- | --- |
| `enum class Condition(@JsonValue val v: String)` | `type Condition = "new" \| "used"` | Jackson writes a scalar. An object type would let `book.condition.v` compile and read `undefined`. |
| `@JvmInline value class Isbn(val value: String)` | `type Isbn = string` | Jackson unwraps it. `{ value: string }` describes the Kotlin declaration, not the JSON. |
| `sealed interface BorrowResponse` | `BorrowAccepted \| BorrowRefused` | The base has no shape of its own on the wire. Emitting its own members describes none of the variants. |
| `val note: String? = null` in a response | `note: string \| null` — **required** | A default decides *what* is written, not *whether*. Only `@JsonInclude(NON_NULL)` can drop a field. |
| `val tags: List<String> = emptyList()` in a request | `tags?: string[]` | A request reads the other way: whatever Kotlin can default, the caller may omit. |
| `RestResponse<Void>` | `Promise<void>` | The status is the whole answer, and `response.json()` throws on an empty body. |
| `@BeanParam BookQuery` | `{ q?: string; limit?: number }` | On the wire a bean param is not a thing; its fields are ordinary query parameters. |
| `@DefaultValue("50") limitRaw: Int` | `` /** Server default: `50`. */ limit?: number `` | Named as the wire names it, and the caller is told what omitting it means. |

Anything it cannot express honestly, it refuses to emit. A header parameter the caller would have to
set itself fails the build rather than producing a call that compiles and silently omits it.

## The shape of the client

Paths become a lazy tree: a literal segment is a property, a variable one a method, and the verb
sits at the leaf.

```ts
api.shelves.get();                                   // GET  /api/shelves
api.shelves.shelf("attic").books.get({ q: "lisp" }); // GET  /api/shelves/attic/books?q=lisp
api.shelves.shelf("attic").books.post(body);         // POST /api/shelves/attic/books
api.shelves.shelf("attic").books.isbn(id).return.post();
```

The verb is at the leaf because a node is routinely both an endpoint and a namespace —
`POST …/books` is create and `…/books/{isbn}` hangs below it. Anywhere else needs a rule for the
clash; at the leaf needs none. A reserved word is a legal property name, so `…/return` stays
`…return.post()`.

Every call takes `(…, options?: RequestOptions)` for `signal`, `timeoutMs`, `headers` and `cache`.
The credential is not there on purpose: it comes from `createApi({ token })` and is closed over by
every leaf, so a server process that serves many users cannot leak one caller's token to the next.
The module-level `api` is for the browser, where the cookie is the credential.

## Using it

**1. Annotate the resources you want a client for.** Opt-in, so a new resource is outside the client
until someone says so — the review point for "is this part of the contract the browser calls", which
the path alone cannot answer.

```kotlin
dependencies {
    implementation("dk.lldata:quarkus-kotlin-typescript-rest-client-annotations:0.1.0-SNAPSHOT")
    testImplementation("dk.lldata:quarkus-kotlin-typescript-rest-client-generator:0.1.0-SNAPSHOT")
}
```

The annotations are a separate artifact with no dependencies, because they go on your resources and
therefore onto your runtime classpath. The generator is a build-time tool and never ships.

**2. Configure it once, in your test source set** — where the writer and the drift gate can be the
same code, and where nothing in the deployed application can reach it. Copy these three files; they
are the entire wiring:

- [ExampleClient.kt](example/backend/src/test/kotlin/dk/lldata/tsclientgen/example/codegen/ExampleClient.kt)
  — the options, the classes directory, and where the two files go.
- [GenerateApiClient.kt](example/backend/src/test/kotlin/dk/lldata/tsclientgen/example/codegen/GenerateApiClient.kt)
  — the `main` the Gradle task runs. The only thing that writes.
- [example/backend/build.gradle.kts](example/backend/build.gradle.kts) — the `generateApiClient`
  task, and `printApiModel` for when you want to see what the emitter is working with.

**3. Commit the client, and gate it.** Two one-line tests, in
[GeneratedClientDriftTest.kt](example/backend/src/test/kotlin/dk/lldata/tsclientgen/example/codegen/GeneratedClientDriftTest.kt):
`driftAgainst(clientFile) shouldBe null` and `lint().check()`.

Without the first, a backend change lands green and the client disagrees with it until someone hits
the mismatch at runtime.

## The contract lint

The generator can only be as honest as the API lets it be, so the rules that make it honest are
checkable. `lint().violations()` returns them all; `check(rules)` throws, narrowed to the rules a
project has adopted.

| Rule | What it prevents |
| --- | --- |
| `TYPED_REQUEST_BODY` | A `String` body, which the client can only be told is `string`. |
| `TOP_LEVEL_RESPONSE_TYPE` | A nested class, which has no flat TypeScript name. |
| `RESPONSE_NAMING` | A payload that is neither `*Response` nor declared shared vocabulary. |
| `SEALED_SUBTYPES_COMPLETE` | A `@JsonSubTypes` list that has drifted from the sealed subclasses, silently dropping an arm. |
| `VARIANT_LITERAL_IN_DISCRIMINATOR` | A variant mapped to a value its discriminator cannot hold. |
| `GET_OR_POST_ONLY` | Four verbs where the action belongs in the path. An opinion — filter it out if you disagree. |
| `USABLE_PATH_SEGMENTS` | A segment that is not a usable member name, or two nodes with one factory name. |
| `ENUM_QUERY_PARAM_CONVERTIBLE` | The trap below. |

**The `@JsonValue` trap.** `@JsonValue` governs the JSON body and nothing else. A query parameter is
converted by JAX-RS, which uses `valueOf` — so a client sending `?condition=worn` (what Jackson
writes everywhere else) gets a 404 while `?condition=WORN` works. The fix is a static `fromString`
on the enum; the rule is what stops the next enum from forgetting.

## Configuration

`GeneratorOptions` holds everything that is a project's decision rather than the generator's. Only
`dtoPackages` has no defensible default — without it the generator cannot tell your payload types
from a JDK class that happens to be reachable.

| Option | Default | |
| --- | --- | --- |
| `dtoPackages` | — | Package prefixes your payload types live under. |
| `rootPath` | `/api` | The prefix every endpoint shares. It becomes the root node. |
| `runtimeImport` | `./client-runtime` | Where the emitted file imports its transport from. |
| `header` | | The comment at the top. Say how to regenerate. |
| `defaultInstanceName` | `api` | Set to null for a server-side client, so `createApi({ token })` is the only way in. |
| `ambientHeaders` | cookie, x-forwarded-for, x-real-ip | Headers the caller never sets. Anything else refuses. |
| `wrapperTypes` | `RestResponse`, `Uni`, `CompletionStage`, … | Return types that wrap the payload rather than being it. Matched by name, so no Quarkus artifact is needed. |
| `scalars` | Kotlin primitives, `java.time`, `UUID`, … | Types that already have a TypeScript spelling. Extend it for anything your serializer flattens. |

## The transport

`writeRuntimeTo` drops a dependency-free `client-runtime.ts` next to the client: `fetch`, a query
builder that omits absent parameters, `AbortSignal` timeouts, and an `ApiError` carrying the status
and the parsed body. It is the only file that knows how a request is made.

Replace it whenever the transport carries project policy — an auth refresh, a proxy hop, retries,
tracing headers. Keep the three exported names (`ApiConfig`, `RequestOptions`, `apiRequest`), point
`runtimeImport` at your module, and the generated client needs no regeneration.

Error bodies are deliberately outside the generated types: `ApiError.body` is `unknown`. The client
types describe what a *successful* call returns.

## Formatting

The emitter builds structure, not text, and leaves layout to the writer. If your repo formats
TypeScript, pass `ClientGenerator` a `formatter = Prettier(binary, filePath)`
([SourceFormatter.kt](generator/src/main/kotlin/dk/lldata/tsclientgen/format/SourceFormatter.kt)) so
the committed file needs no formatting exemption.

Give the drift check the same instance — they must not disagree about what "generated" means. It
refuses rather than falling back to unformatted output: a drift check comparing two unformatted
strings passes while the committed file is wrong.

The example does not use it, so that a fresh clone builds and tests green with no JavaScript
toolchain at all.

## The example

`example/` is a working consumer, not a snippet: a Quarkus resource layer that models a bookshelf
and uses every feature above, the generated client committed under `example/web/src/generated/`, and
a page that calls it.

```bash
(cd example/web && npm ci && npm run build)
./gradlew :example:backend:quarkusDev
# http://localhost:8088
```

See [example/README.md](example/README.md) for what to look at and in which order.

## Building this repo

```bash
./gradlew build
```

`:generator` is the library, `:annotations` is the two annotations, `:example:backend` is the
consumer. `./gradlew ktlintFormat` before committing.

### The TypeScriptPoet jar

Declarations are built with [lldata/typescriptpoet](https://github.com/lldata/typescriptpoet), a
fork of `outfoxx/typescriptpoet` — upstream stopped at 1.1.2 in 2021, and the fork adds a Kotlin DSL
and the object-literal getter a lazy call tree is made of.

It is unpublished, so `libs/typescriptpoet-2.0.0-alpha6.jar` is vendored and committed, and the
published POM cannot declare it. **This is why the artifact is `0.1.0-SNAPSHOT` and not released**;
publishing the fork is the prerequisite. Upgrading means dropping in a new jar and bumping the
filename in `generator/build.gradle.kts`.

## Origin

Extracted from the API client generator in the lokl codebase, where it emits a 2,300-line client for
fifty endpoints and has been kept honest by a drift gate and a contract lint in CI. Generalising it
turned every hardcoded decision into a `GeneratorOptions` field, and building the example found a
real gap the original never hit: a type reached only through a query parameter was used in the call
signature and never declared.
