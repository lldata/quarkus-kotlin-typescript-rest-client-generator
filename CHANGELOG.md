# Changelog

All notable changes to this project are documented here, following
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

The versions below are tags, not published releases: nothing has reached Maven Central yet. Until
it does, the artifacts are available through `./gradlew publishToMavenLocal` — see the README.

## [Unreleased]

### Added

- **An optional stamp in the emitted client, so a copy of it can tell it is stale.** Inside a
  repository the drift test is the gate; outside one there was nothing, and a vendored client — a
  second service that cannot run the generator, because that needs the backend's compiled classes —
  found out it had drifted when a call failed. `GeneratorOptions.stamp` emits a value the consumer
  treats as opaque and the server that minted it can decompose:

  ```ts
  export const API_STAMP: string = "4.2.0.9f3c1a2e";
  export const API_STAMP_HEADER: string = "x-api-stamp";

  export function createApi(baseConfig: ApiConfig = {}) {
    const config: ApiConfig = {
      ...baseConfig,
      headers: { "x-api-stamp": API_STAMP, ...baseConfig.headers },
    };
  ```

  A `const` rather than only a comment, because a comment can be diffed and not sent. The
  composition is the project's: the generator supplies an `ApiDigest` — a hash over the emitted
  contract, plus the plain-text projection it hashed — and `StampOptions.value` decides what the
  string says. The generator contributes no version of its own, which would have meant mandating a
  version resource for a string the consuming build script can already interpolate. Naming
  `StampOptions.header` sends it on every call from `createApi`; leaving it null exports the
  constants and sends nothing, since a default header name would put one this library invented on
  the wire. `ClientGenerator.writeStampTo` writes the same value onto the backend's classpath, so a
  server compares rather than merely receives — the filter or endpoint that reads it is a few lines
  a project writes, and the generator emits neither. The example has the filter, warning once per
  distinct stamp when a caller's client was generated against a different API than the backend
  serves.

  The hash is over the contract rather than the emitted bytes, so it reports incompatibility rather
  than churn: a doc comment, a layout decision, or `runtimeImport` being rewritten by a vendoring
  script leaves it where it was. Nothing refuses on a mismatch, at either end — most backend
  changes are additive, and "is this compatible" is not a question a hash comparison answers. Off
  by default. ([#7](https://github.com/lldata/quarkus-kotlin-typescript-rest-client-generator/issues/7))

- **The contract lint refuses an emitted name that shadows a standard-library global.** An
  exported name that is also a global breaks every file that imports it, and the diagnostic reads
  like a compiler bug — it names the same type on both sides and separates them only by absolute
  path. A payload named `Response` produces "Property 'text' does not exist on type 'Response'" at
  the `fetch` call site, in a file that never mentioned the client's `Response`. Every exported
  declaration is checked, since they all land in one namespace. The list of globals is curated
  rather than exhaustive: `lib.dom.d.ts` has hundreds of names, and checking them all would flag
  `Text` in a project that never touches the DOM. Like every rule, it can be filtered out by a
  project that is sure it never imports the DOM. ([#2](https://github.com/lldata/quarkus-kotlin-typescript-rest-client-generator/issues/2))

- **Every `ScalarStyle` is now covered by a test that checks its alias against the global list.**
  The shipped scalar table avoids every global today, which #2 rightly called luck rather than
  judgement — `java.net.URI` is aliased `URI`, and `URL` would have collided. The next scalar
  added has to choose its name deliberately.

### Fixed

- **The client imported its two runtime types as values, which two ordinary consumer setups
  reject.** `ApiConfig` and `RequestOptions` are types, and the emitted import did not say so.
  Under `verbatimModuleSyntax` the import is emitted as written, so `tsc` fails with TS1484; under
  a runtime that *strips* types rather than compiling them — `node --experimental-strip-types`,
  and the same in Deno and Bun — the binding survives to run time and the module has no such
  export. The Temporal module had the same gap and needed a finer answer: a branded scalar is
  imported as a value, because the conversion calls its companion, while a named one is a bare
  alias that only ever appears in a type position. Nothing changes for a project that elides
  today — both forms erase to the same JavaScript. The example now sets `verbatimModuleSyntax`,
  so the emitted file is checked in the shape that catches this rather than the one that forgives
  it. ([#6](https://github.com/lldata/quarkus-kotlin-typescript-rest-client-generator/issues/6))

- **A query parameter whose wire name is not a valid identifier emitted invalid TypeScript.** A
  parameter named `page-size` became `{ page-size: string }`, which does not parse. Non-identifier
  wire names are now quoted.

- **The path lint read the Kotlin name rather than the emitted one**, so it could pass on a path
  whose emitted form was the problem.

- **Several constructs were handed to the writer pre-joined**, as one opaque token it could not
  break, and so ran past the print width: the Temporal module's annotations, an anonymous arrow,
  and the shipped runtime. Each is now built as structure the writer can lay out.

### Changed

- **The example's backend is split into `api`, `api.stamp` and `domain`**, which it had outgrown as
  one flat package. The line falls where the example already drew it: `api` holds the things that
  know about HTTP — the resources, the `@QueryParam` holder, the mapper deciding which status a
  failure is worth, the stamp filter — and `domain` holds the data and the catalogue. The
  request and response types are in `domain` with everything else, because none of them is
  HTTP-shaped: no status, no headers, no cookies, and a refused loan is a 200 the client narrows
  on a field. Returning a domain type straight from a resource stays a normal thing to do, which
  `GET /api/shelves` has always done.

  It also shows two things one package could not: `dtoPackages` is a prefix, covering a package
  below the one named; and neither the emitted client nor its stamp moved, because the digest is
  over the contract and Kotlin package structure is not part of it.

- **The scalar guard's argument is broken across lines**, so the emitted output is a Prettier
  fixed point rather than something Prettier would reformat on contact.
  ([#5](https://github.com/lldata/quarkus-kotlin-typescript-rest-client-generator/issues/5))

## [1.0.0-alpha4] — 2026-08-15

### Added

- **`UntypedPolicy`: what the generator cannot describe is now a policy rather than a wall.** Some
  return types hold a body without declaring it — `Response`, the idiomatic plain-JAX-RS return,
  and a bare `Any`, which reaches the client as `unknown`. There is no good answer for either, so
  the behaviour is a setting: `Fail` (default), `Warn` (emit `unknown` and report it), `Ignore`.
  `Warn` is the adoption path for a codebase that predates the generator: the client generates,
  the caller is told `unknown` rather than something plausible and wrong, and the build says what
  it could not describe.

- **The `UNTYPED_PAYLOAD` lint rule**, which reports the whole set at once. The emitter refuses one
  endpoint at a time, which is no way to discover that fifty are like this. Emitter and lint share
  one `untypedPayload`, so they cannot disagree about what counts.

### Fixed

- **The refusal for `Response` gave the wrong advice.** It told you to add
  `jakarta.ws.rs.core.Response` to `dtoPackages` or map it as a scalar, and neither is the fix. It
  now says the payload is undeclared and names the ways out.

### Changed

- **The "not Quarkus-specific" claim is now something that has been run rather than asserted**,
  by building a plain Jersey consumer with no Quarkus on the classpath. The README says what it
  costs: JAX-RS yes, Spring and Ktor no.

## [1.0.0-alpha3] — 2026-08-15

### Fixed

- **The Temporal module named the global `Temporal`, which does not exist on every TypeScript.**
  The README told projects to set `lib: "esnext"` and be done; that is only true on a TypeScript
  shipping `lib.esnext.temporal.d.ts` — 7.0 does, 5.9 does not, and no `lib` setting conjures one
  that is not there. The polyfills do not close the gap either: they export `Temporal` as a module
  member and declare no global. So the documented path produced a module that could not typecheck
  and said nothing about why. `writeTemporalTo(…, temporalImport = "@js-temporal/polyfill")` now
  emits a real import. ([#4](https://github.com/lldata/quarkus-kotlin-typescript-rest-client-generator/issues/4))

### Added

- **The publishing configuration for Maven Central** — signed publications, POM metadata, a Dokka
  javadoc artifact, and a tagged release workflow that uploads a bundle to the Central Portal for
  a human to publish.

## [1.0.0-alpha2] — 2026-08-15

### Fixed

- **Property names were read from Kotlin rather than from the wire.** A property renamed for
  serialization — by `@JsonProperty`, or by a naming strategy — was emitted under its Kotlin name,
  so the client asked for a field the server does not send. ([#3](https://github.com/lldata/quarkus-kotlin-typescript-rest-client-generator/issues/3))

- **A missing `kotlin-reflect` failed with a message that named the wrong cause.** It now says
  what is actually absent and how to add it.

## [1.0.0-alpha1] — 2026-08-15

The first tagged version, extracted from the application it grew inside.

### Added

- **The generator**: reads compiled JAX-RS resources, Jackson annotations and Kotlin metadata, and
  emits a TypeScript client with a nested call tree.
- **The two annotations**, in a module with no dependencies, since they land on a consumer's
  runtime classpath.
- **`ScalarStyle`**: how much identity a wire scalar keeps on the way through — inlined, named, or
  branded — as a per-type choice rather than a single global rule.
- **A Temporal conversion module**, emitted alongside the client, for the scalars that carry a
  date or a time.
- **The contract lint**, so the rules that keep the emitted client honest are checkable rather
  than a matter of trust.
- **A refusal for a type with no spelling**, in place of emitting a dangling name that no
  declaration backs.
- **The example**: a Quarkus resource layer, the generated client, and a page written against it,
  with a drift test that fails when the committed client no longer matches what the generator
  would emit.
