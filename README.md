# quarkus-kotlin-typescript-rest-client-generator

Generates a TypeScript client from annotated Jakarta RESTful Web Services (JAX-RS) Kotlin resources,
by reading the compiled classes rather than an OpenAPI document.

Quarkus is what it was built against and what the example uses, but nothing in it depends on Quarkus
— it reads `jakarta.ws.rs` and Jackson annotations, both `compileOnly`. It has been run against a
plain Jersey application with no Quarkus on the classpath at all. See *Outside Quarkus* below for
what that costs.

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

The example runs `quarkus-smallrye-openapi` alongside the generator so that claim is checkable
rather than rhetorical: on the same resources, OpenAPI spells a `@JsonValue` enum as its constant
names, reduces a sealed hierarchy to the base's own member, and marks a `@JsonInclude(NON_NULL)`
field required. [The comparison](example/README.md#the-control-group-what-openapi-says-about-the-same-resources)
has the four cases side by side, including the one OpenAPI describes better.

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
| `@JsonProperty("display_name")` | `display_name: string` | The wire's name, not Kotlin's. Emitting `displayName` gives a client that compiles and reads `undefined`. |
| `@JsonIgnore val internal: String` | *omitted* | Jackson never writes it, so the client must not claim to have it. |
| `@DefaultValue("50") limitRaw: Int` | `` /** Server default: `50`. */ limit?: number `` | Named as the wire names it, and the caller is told what omitting it means. |
| `java.time.Instant` | `type Instant = string` | ISO-8601 on the wire, but named rather than inlined — the client can still say it is a timestamp. Inline or brand it instead: see [`Scalar`](generator/src/main/kotlin/dk/lldata/tsclientgen/Scalar.kt). |

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

**0. Get the artifacts.** Not on Maven Central yet, so there are two ways in.

*On a developer machine*, install into `~/.m2` and add `mavenLocal()` to the consuming build:

```bash
./gradlew publishToMavenLocal
```

*Anywhere a build runs without your `~/.m2`* — a CI runner, most obviously — take the jars from the
[release](https://github.com/lldata/quarkus-kotlin-typescript-rest-client-generator/releases), commit
them to a `libs/` directory and depend on the files. **A `files(…)` dependency carries no
transitives**, so the generator's own two come by hand:

```kotlin
repositories { mavenCentral() }

dependencies {
    implementation(files("libs/quarkus-kotlin-typescript-rest-client-annotations-1.0.0-alpha4.jar"))

    testImplementation(files("libs/quarkus-kotlin-typescript-rest-client-generator-1.0.0-alpha4.jar"))
    testImplementation("dk.lldata:typescriptpoet:2.0.0-alpha10")
    testImplementation(kotlin("reflect"))
}
```

Leave `kotlin("reflect")` out and every endpoint is dropped — the generator checks for it and says
so, rather than reporting the symptom, which reads like a wrong directory.

**1. Annotate the resources you want a client for.** Opt-in, so a new resource is outside the client
until someone says so — the review point for "is this part of the contract the browser calls", which
the path alone cannot answer.

```kotlin
repositories {
    mavenLocal()     // where the two artifacts below are, until they are published
    mavenCentral()   // where dk.lldata:typescriptpoet is
}

dependencies {
    implementation("dk.lldata:quarkus-kotlin-typescript-rest-client-annotations:1.0.0-alpha4")
    testImplementation("dk.lldata:quarkus-kotlin-typescript-rest-client-generator:1.0.0-alpha4")
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
| `UNTYPED_PAYLOAD` | An endpoint the generator cannot describe — see *Untyped payloads*. Reported for the whole surface at once, whatever the policy. |

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
| `scalars` | `scalars(ScalarStyle.Named)` | How much identity a wire scalar keeps — see below. Per-type overrides are ordinary map entries. |

## Untyped payloads

Some return types hold a body without declaring it. `jakarta.ws.rs.core.Response` is the obvious
one, and it is *the idiomatic return in plain JAX-RS* — a Jersey or Jakarta EE surface can be full
of them. A bare `Any` is the other: it reaches the client as `unknown`, which tells a caller nothing.

There is no good answer the generator can give, so it is a setting:

| `UntypedPolicy` | |
| --- | --- |
| `Fail` *(default)* | Refuse, naming the endpoint and what to do instead. |
| `Warn` | Emit `Promise<unknown>` and report it through `onWarning`. |
| `Ignore` | Emit `Promise<unknown>` silently. The lint still reports it. |

`Warn` is the adoption setting for a codebase that predates the generator: the client generates, the
caller is told `unknown` rather than something plausible and wrong, and the build says what it could
not describe. Fix them, then switch to `Fail` so no new one appears.

The emitter refuses one endpoint at a time, which is no way to discover that fifty are like this, so
`UNTYPED_PAYLOAD` in the contract lint reports the whole set before you start.

## Scalars: how much identity a `string` keeps

A timestamp, an ISBN and a title are all `string` on the wire. What the client is told about that is
a project's decision, so it is a policy rather than a default nobody chose:

| `ScalarStyle` | emits | costs |
| --- | --- | --- |
| `Inline` | `addedAt: string` | nothing, and says nothing |
| `Named` *(default)* | `export type Instant = string` | nothing — an alias is structurally the same type |
| `ValueObject` | a brand plus a companion to construct through | a constructor call where a client *creates* one |

```kotlin
GeneratorOptions(
    dtoPackages = listOf("com.example.api"),
    scalars = GeneratorOptions.scalars(ScalarStyle.ValueObject) +
        ("java.time.Duration" to Scalar.inline("string")),   // exceptions are map entries
)
```

`ValueObject` emits this, and the type and the companion share a name — as close as TypeScript gets
to a Kotlin companion object, and it reads like the library it mirrors:

```ts
export type Instant = string & { readonly __brand: "Instant" };

export const Instant: CheckedScalar<string, Instant> = {
  from: (raw: string): Instant => {
    if (!Instant.is(raw)) throw new TypeError(`invalid Instant: ${raw}`);
    return raw;
  },
  is: (raw: string): raw is Instant => /^(?:\d{4}-\d{2}-\d{2}T…)$/u.test(raw),
};
```

**Reading costs nothing.** A brand is an intersection with `string`, so every string method,
comparison and interpolation still works — `new Date(book.addedAt)` and
`a.addedAt.localeCompare(b.addedAt)` are unchanged. The cost lands where a client *constructs* a
value: `Instant.from(x)` rather than a bare string. That is the point — the string it refuses is
usually one that came from a text input and was never checked.

Where the generator knows the format — the `java.time` family, `UUID` — the companion validates.
The pattern is anchored `^(?:…)$` on purpose: Bean Validation's `@Pattern` uses `Matcher.matches()`,
which must match the whole input, while a JavaScript `test()` searches for a substring, so an
unanchored port accepts `"abc123xyz"` in the client and is rejected by the server. Where the format
is unknown, `from` brands without checking and says so rather than pretending.

`Named` stays the default because branding changes call sites, and an upgrade should not silently
stop a project's request bodies compiling.

### Converting to `Temporal`

`writeTemporalTo(file)` emits a second module for the date scalars, and returns null when nothing on
the surface converts — no file rather than an empty one:

```ts
import { toTemporal, fromTemporal } from "./generated/client-temporal.js";

toTemporal.instant(book.addedAt);      // Temporal.Instant
fromTemporal.instant(someInstant);     // back to the wire type, validated if branded
```

It is cheap because ISO-8601 is what both sides already speak: the string the server writes is the
format `Temporal` parses, so there is no conversion layer and nothing runs per response.

Separate from the client because naming `Temporal.Instant` in `client.ts` would put its requirements
on every project that imports the client. Importing this module is how a project says it has decided
— and there are two decisions, not one.

**At runtime**, `Temporal` is not everywhere: [caniuse](https://caniuse.com/temporal) put it at 69%
of global usage in July 2026, the gap being older Safari and iOS, and Node 24 needs
`--harmony-temporal` (where the API is an older draft than the browsers').

**At compile time**, the `Temporal` *types* come from TypeScript's own `lib.esnext.temporal.d.ts`,
which 7.0 ships and 5.9 does not — and no `lib` setting conjures it on a TypeScript that lacks it.
The polyfills do not help by themselves either: they export `Temporal` as a module member and
declare no global. On an older TypeScript, say where it lives and the module imports it:

```kotlin
generator.writeTemporalTo(file, clientImport = "./client.js", temporalImport = "@js-temporal/polyfill")
```

which emits `import { Temporal } from "@js-temporal/polyfill";` and typechecks on 5.9. The default
uses the global, which is right for TypeScript 7. The emitted file says which it is using.

## Outside Quarkus

The generator reads JAX-RS annotations, so it works with any framework that uses them — Jersey,
Jakarta EE, Helidon, RESTEasy standalone. It does **not** work with Spring or Ktor, which have their
own routing annotations and are not JAX-RS at all; there is nothing for it to read, and it says so
rather than emitting an empty client.

The one real friction is `jakarta.ws.rs.core.Response`. Quarkus resources tend to return the payload
type or `RestResponse<T>`, both of which say what they carry. Plain JAX-RS idiomatically returns
`Response`, which does not — see *Untyped payloads*. That is not a Quarkus feature so much as the
problem `RestResponse<T>` was added to solve, and it is why a Quarkus surface tends to generate
cleanly while a Jersey one may need `UntypedPolicy.Warn` first.

## The transport

`writeRuntimeTo` drops a dependency-free `client-runtime.ts` next to the client: `fetch`, a query
builder that omits absent parameters, `AbortSignal` timeouts, and an `ApiError` carrying the status
and the parsed body. It is the only file that knows how a request is made.

Replace it whenever the transport carries project policy — an auth refresh, a proxy hop, retries,
tracing headers. Keep the three exported names (`ApiConfig`, `RequestOptions`, `apiRequest`), point
`runtimeImport` at your module, and the generated client needs no regeneration.

Error bodies are deliberately outside the generated types: `ApiError.body` is `unknown`. The client
types describe what a *successful* call returns.

It assumes a recent runtime: `AbortSignal.any` and `AbortSignal.timeout` combine a caller's abort
with a per-call timeout, which is the newest thing it uses ([caniuse](https://caniuse.com)). Replace
the module if you need to support older engines — that is what the seam is for.

## Formatting

The emitter builds structure, not text, and leaves layout to the writer. If your repo formats
TypeScript, pass `ClientGenerator` a `formatter = Prettier(binary, filePath)`
([SourceFormatter.kt](generator/src/main/kotlin/dk/lldata/tsclientgen/format/SourceFormatter.kt)) so
the committed file needs no formatting exemption.

**Do not format the generated files with your own formatter instead.** Two authorities over one file
agree only by coincidence: your formatter rewrites what the emitter wrote, `driftAgainst` then reports
the file as stale, and regenerating and reformatting never converge. Either exclude the generated
directory from your formatter, or pass that same formatter here so both gates are looking at the same
bytes. The emitted output is a Prettier fixed point at default settings, so with no formatter
configured at all, excluding the directory costs nothing.

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
./gradlew build                # library, annotations, example, drift test and lint
./gradlew publishToMavenLocal  # installs both artifacts into ~/.m2 for other projects
```

`:generator` is the library, `:annotations` is the two annotations, `:example:backend` is the
consumer. `./gradlew ktlintFormat` before committing.

The version is set once, in `gradle.properties`, so the two published artifacts cannot drift apart.

### Publishing

`publishToMavenLocal` is the whole story for now — there is no repository configured to publish to,
deliberately. Maven Central would need signing keys, a Portal account and a `repositories` block in
the publication; nothing in the code stands in the way any more, since the last blocker (a vendored
jar that a POM cannot declare) is gone.

### TypeScriptPoet

Declarations are built with [lldata/typescriptpoet](https://github.com/lldata/typescriptpoet)
(`dk.lldata:typescriptpoet`), a fork of `outfoxx/typescriptpoet` — upstream stopped at 1.1.2 in
2021. The fork adds a Kotlin DSL, the object-literal getter a lazy call tree is made of, and a
writer that breaks a call's argument list on width rather than running it off the line.

It is on Maven Central, so there is nothing vendored here and nothing stopping this library being
published in turn.

## Origin

Extracted from the API client generator in the lokl codebase, where it emits a 2,300-line client for
fifty endpoints and has been kept honest by a drift gate and a contract lint in CI. Generalising it
turned every hardcoded decision into a `GeneratorOptions` field, and building the example found a
real gap the original never hit: a type reached only through a query parameter was used in the call
signature and never declared.
