// Generated from the Kotlin resources. Do not edit: fix the resources, or
// the generator, and re-run `./gradlew :example:backend:generateApiClient`.
import { Instant } from "./client.js";

export const toTemporal: { readonly instant: (value: Instant) => Temporal.Instant } = {
  instant: (value: Instant): Temporal.Instant => Temporal.Instant.from(value),
};

export const fromTemporal: { readonly instant: (value: Temporal.Instant) => Instant } = {
  instant: (value: Temporal.Instant): Instant => Instant.from(value.toString()),
};
