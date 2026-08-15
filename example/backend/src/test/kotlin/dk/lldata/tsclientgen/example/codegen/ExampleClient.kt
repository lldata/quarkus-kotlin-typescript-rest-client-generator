package dk.lldata.tsclientgen.example.codegen

import dk.lldata.tsclientgen.ClientGenerator
import dk.lldata.tsclientgen.GeneratorOptions
import dk.lldata.tsclientgen.ScalarStyle
import java.io.File

/**
 * How this project generates its client. The whole configuration is here, so the write task and the
 * drift test cannot disagree about it.
 *
 * It lives in `src/test` because the generator is a build-time tool: nothing in the deployed
 * application depends on it, and the drift gate is the same code as the writer.
 */
object ExampleClient {
    private val options =
        GeneratorOptions(
            dtoPackages = listOf("dk.lldata.tsclientgen.example"),
            // The example opts into the strongest of the three: `Instant` is branded, and the page
            // converts it with the emitted Temporal module. The brand costs nothing here — the page
            // only ever reads timestamps — and `Isbn` is a Kotlin value class rather than a scalar,
            // so it is unaffected either way.
            scalars = GeneratorOptions.scalars(ScalarStyle.ValueObject),
            // `.js` on purpose: the browser loads the compiled output as ES modules, where the
            // extension is required. TypeScript resolves `./client-runtime.js` to the `.ts` next to
            // it, so both the type check and the browser are happy.
            runtimeImport = "./client-runtime.js",
            // Wrapped to the same 80 columns Poet lays the rest of the file out to, so the
            // comment is not the only thing in the file that overruns.
            header =
                "Generated from the Kotlin resources. Do not edit: fix the resources, or\n" +
                    "the generator, and re-run `./gradlew :example:backend:generateApiClient`.",
        )

    /** Where the web app keeps its generated sources. All three are committed. */
    private val generatedDir = File(repoRoot(), "example/web/src/generated")

    val clientFile: File = File(generatedDir, "client.ts")
    val runtimeFile: File = File(generatedDir, "client-runtime.ts")
    val temporalFile: File = File(generatedDir, "client-temporal.ts")

    /**
     * The compiled resources the model is read from.
     *
     * The build layout, not `codeSource.location`: that points at the classes directory or at the
     * jar depending on whether the `jar` task has run, and a jar walked as a directory yields no
     * endpoints — a gate that passes precisely when it is broken.
     */
    fun generator(): ClientGenerator =
        ClientGenerator(
            classesDir = File(repoRoot(), "example/backend/build/classes/kotlin/main"),
            options = options,
        )

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("could not find the repo root from ${File(".").absolutePath}")
        }
        return dir
    }
}
