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

*From anywhere a build runs* — a CI runner, a colleague's machine — through
[JitPack][jitpack], which builds a commit of this repository on demand. Nothing is published for
that to work and no release has to be cut:

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    val client = "com.github.lldata.quarkus-kotlin-typescript-rest-client-generator"

    implementation("$client:quarkus-kotlin-typescript-rest-client-annotations:main-SNAPSHOT")
    testImplementation("$client:quarkus-kotlin-typescript-rest-client-generator:main-SNAPSHOT")
}
```

Two things about that coordinate. The group is the repository's rather than `dk.lldata`, because
JitPack builds from git — and it carries the repository name because this is a multi-module build,
where the artifact id stays what the module publishes. Both lines change to `dk.lldata` at a
release.

And `main-SNAPSHOT` is the convenient version, not the good one. It moves, so a bug report against
it is not reproducible; a commit — `…-generator:2bf36ed` — does not. Pin one as soon as you are
reporting anything. The first request for a given commit takes a minute or two while JitPack builds
it, and is cached after that.

*On a developer machine*, install into `~/.m2` and add `mavenLocal()` to the consuming build:

```bash
./gradlew publishToMavenLocal
```

Either way the generator brings `kotlin-reflect` with it. Exclude it and every endpoint is dropped —
the generator checks and says so, rather than reporting the symptom, which reads like a wrong
directory.

**What it is tested against.** The example is a real Quarkus application that runs the generator
against the platform's own classpath, and it tracks the current Quarkus release — so that is the
version this library is tested with, and the floor moves with the platform rather than lagging it.
An older Quarkus will usually work, because the generator compiles against `jakarta.ws.rs` 3.1.0
and `jackson-annotations` 2.17.0 and reads whatever annotations your classes actually carry. But
"usually" is the honest word: nothing here checks it.

**1. Annotate the resources you want a client for.** Opt-in, so a new resource is outside the client
until someone says so — the review point for "is this part of the contract the browser calls", which
the path alone cannot answer.

```kotlin
repositories {
    mavenLocal()     // the developer-machine route above; JitPack is the other
    mavenCentral()   // where the generator's own dependencies are
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
| `SHADOWS_A_GLOBAL` | An exported name that is also a standard-library global — the trap below. |

**The shadowing trap.** A Kotlin class named `Location`, `Event`, `Record` or `Response` is an
ordinary domain noun and also a global. Exported from the client, the import wins for the whole
importing file:

```ts
import type { Response } from "./client.js";
const real: Response = await fetch("/api"); // the client's Response, not the platform's
return real.text();                         // TS2339: Property 'text' does not exist on type 'Response'
```

The diagnostic names the same type on both sides and separates them only by absolute path, so the
first guess is that the compiler is broken. Every exported declaration is checked — interfaces,
enum aliases, value-class aliases, union aliases and scalar aliases all share one namespace. The
list of globals is curated rather than exhaustive, so that `Text` is not flagged in a project that
never touches the DOM; filter the rule out if yours is one of those. For a scalar the escape hatch
is `Scalar.named("Timestamp", "string")`, and for a payload it is renaming the Kotlin.

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
| `stamp` | off | Provenance the client carries, so a copy of it can tell it is stale — see below. |

## Stamping the client

Inside your repository, a drift test is the gate: regenerate, compare, fail. Outside it there is
nothing. A vendored copy — a second service that cannot run the generator, because that needs the
backend's compiled classes — can only establish that it is current by cloning the backend and
regenerating, so in practice it finds out when a call fails.

A stamp is what that copy has instead. It is off by default; naming a `stamp` turns it on.

```kotlin
GeneratorOptions(
    dtoPackages = listOf("com.example.api"),
    stamp = StampOptions(
        value = { "${System.getProperty("myapp.version")}.${it.short}" },
        header = "x-api-stamp",
    ),
)
```

```ts
export const API_STAMP: string = "4.2.0.9f3c1a2e";
export const API_STAMP_HEADER: string = "x-api-stamp";

export function createApi(baseConfig: ApiConfig = {}) {
  const config: ApiConfig = {
    ...baseConfig,
    headers: { "x-api-stamp": API_STAMP, ...baseConfig.headers },
  };
  ...
```

A `const` rather than only a comment in the header, because a comment can be diffed and not sent.
The consumer treats it as opaque — it compares it and reports a mismatch, never parses it — so the
composition can change without breaking anyone.

**The composition is yours.** The generator supplies an `ApiDigest`, and `value` decides what to do
with it. `it.short` is a hash of the API shape; `it.modelHash` is the whole SHA-256; `it.contract`
is the plain-text projection the hash is over, so a project that wants a different digest entirely
can hash that instead. The generator deliberately contributes no version of its own: it would have
to read one from a resource this library then had to mandate, and your build file already knows
which generator you depend on. Fold that in yourself — it is worth doing, because an emitter change
can move the emitted file without moving the contract, and a hash over the contract cannot see it.

Nothing in the build script reaches the generator by itself, since the configuration is ordinary
Kotlin. A `JavaExec` task is the seam:

```kotlin
tasks.register<JavaExec>("generateApiClient") {
    // …
    systemProperty("myapp.version", project.version.toString())
}
```

**What moves the hash.** Anything a consumer writes code against: an endpoint, a wire name, a type,
whether a field may be absent, how a scalar is spelled. Not `header`, and not `runtimeImport` — a
vendored copy commonly rewrites the import on the way in, and a stamp that moved on that would
report every such copy as a different client. Everything is sorted before hashing, so reordering a
resource does not move it either.

**The server end is yours too**, and the generator emits no code for it. `writeStampTo` writes the
same composed value onto the backend's classpath, so the server carries the stamp rather than
deriving one:

```kotlin
generator.writeTo(clientFile)
generator.writeStampTo(File("src/main/resources/api-stamp.txt"))
```

From there it is a filter that reads the request header, or an endpoint a consumer's CI can call —
both a few lines you write, against a value you can decompose because you minted it. The example
has the filter, in
[`ApiStampFilter`](example/backend/src/main/kotlin/dk/lldata/tsclientgen/example/ApiStampFilter.kt):

```
WARN  client was generated against API stamp deadbeef, this backend is ca500faf.
      Regenerate it: the two describe different APIs, though the calls it makes may still work.
```

Once per distinct stamp rather than once per request — a stale client calls constantly, and the
thousandth copy of that line carries nothing the first did not. A caller that sends no stamp is
ignored rather than reported: that is curl, or a health check, not a client that has drifted.

If a resource reads the stamp with `@HeaderParam` instead, add the name to `ambientHeaders`: the
client sets that header and the caller never does, which is exactly what that option is for.

**It reports; it does not refuse.** Two stamps differing does not mean the client is broken — most
backend changes are additive, and a client generated before one usually still works. Answering "is
this compatible" needs a compatibility model rather than a hash comparison, so nothing here refuses
on a mismatch, on either end.

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
`runtimeImport` at your module, and the generated client needs no regeneration. Keep the first two
*types* while you are at it: the client imports them as `type`, so a replacement that exports them
as values still works, but one that stops being a type does not.

Every import the client emits says `type` where it means a type, which two ordinary setups need it
to. Under `verbatimModuleSyntax` the import is emitted as written, so a value import of a type is an
error; under a runtime that *strips* types rather than compiling them — `node
--experimental-strip-types`, and the same in Deno and Bun — the binding survives to run time and the
module has no such export. Both forms erase to the same JavaScript, so a project that does neither
sees no difference.

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

There are two channels, and only one of them publishes anything.

**Unreleased commits build on JitPack**, which is what `jitpack.yml` is for. A consumer asks for a
commit, JitPack runs `publishToMavenLocal` on it and serves what comes out; nothing is published
here, no version is spent, and a fix is testable by whoever reported it the moment it merges. That
is worth reaching for before cutting a release, because the thing being tested is emitted TypeScript
compiling in someone else's build.

**Releases are deliberate, and take two steps.** The version is the `version=` line in
`gradle.properties`, not the tag — pushing `v<that version>` builds at it, uploads a signed bundle
to the [Central Portal](https://central.sonatype.com/publishing) and opens a draft GitHub release.
A tag that disagrees with what is committed fails the workflow rather than publishing the wrong
number.

Nothing reaches Maven Central until a human presses Publish there: a released version is permanent
and cannot be recalled, so it stays a deliberate act rather than a consequence of pushing a tag.

### TypeScriptPoet

Declarations are built with [lldata/typescriptpoet](https://github.com/lldata/typescriptpoet)
(`dk.lldata:typescriptpoet`), a fork of `outfoxx/typescriptpoet` — upstream stopped at 1.1.2 in
2021. The fork adds a Kotlin DSL, the object-literal getter a lazy call tree is made of, and a
writer that breaks a call's argument list on width rather than running it off the line.

It is pinned to a commit rather than a release, and comes from [JitPack][jitpack] rather than Maven
Central — hence the extra `repositories` entry and the `com.github.lldata` group. Both repositories
follow the same policy: unreleased work is continuously available from JitPack, which builds a commit
on demand and publishes nothing, and only real releases go to Central. So an alpha of this library
depending on an unreleased commit of that one is the ordinary case, not a corner to get out of. Both
lines move back to `dk.lldata` and Central when TypeScriptPoet 2.0.0 lands, which is what a real
release here would be built against.

The commit is pinned for the reason JitPack's own docs give: a rolling coordinate has moved by the
time anyone reads a bug report against it. The fix it is pinned for wraps an over-wide `import`,
which is what keeps the emitted output a Prettier fixed point now that the transport import carries
`type` twice — it landed the morning after `2.0.0-alpha10` was tagged.

[jitpack]: https://jitpack.io

## Origin

Extracted from the API client generator in the lokl codebase, where it emits a 2,300-line client for
fifty endpoints and has been kept honest by a drift gate and a contract lint in CI. Generalising it
turned every hardcoded decision into a `GeneratorOptions` field, and building the example found a
real gap the original never hit: a type reached only through a query parameter was used in the call
signature and never declared.
