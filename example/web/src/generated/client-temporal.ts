// Generated from the Kotlin resources. Do not edit: fix the resources, or
// the generator, and re-run `./gradlew :example:backend:generateApiClient`.
// This module names `Temporal`. TypeScript's own lib declares it from
// 7.0 (with `lib: "esnext"`), and nothing declares it before that — the
// polyfills export it as a module member and no global at all. On an
// older TypeScript, regenerate with `temporalImport` set to the polyfill
// and this file will import it.
import { Instant } from "./client.js";

export const toTemporal: Readonly<{
  instant: (value: Instant) => Temporal.Instant;
}> = {
  instant: (value: Instant): Temporal.Instant => Temporal.Instant.from(value),
};

export const fromTemporal: Readonly<{
  instant: (value: Temporal.Instant) => Instant;
}> = {
  instant: (value: Temporal.Instant): Instant => Instant.from(value.toString()),
};
