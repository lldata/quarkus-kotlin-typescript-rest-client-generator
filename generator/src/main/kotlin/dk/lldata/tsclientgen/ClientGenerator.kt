package dk.lldata.tsclientgen

import dk.lldata.tsclientgen.emit.ContractDigest
import dk.lldata.tsclientgen.emit.TemporalModuleEmitter
import dk.lldata.tsclientgen.emit.TypeScriptClientEmitter
import dk.lldata.tsclientgen.emit.TypeScriptTypes
import dk.lldata.tsclientgen.format.SourceFormatter
import dk.lldata.tsclientgen.lint.ApiContractLint
import dk.lldata.tsclientgen.model.ApiModel
import dk.lldata.tsclientgen.model.ApiModelReader
import java.io.File

/**
 * The generator, end to end: read the compiled resources, emit the client, format it, write it —
 * or compare it with what is committed.
 *
 * One instance is one client. The model is read once, so the write task and the drift test can
 * share an instance and cannot disagree about what "generated" means.
 *
 * ```kotlin
 * val generator = ClientGenerator(
 *     classesDir = File("build/classes/kotlin/main"),
 *     options = GeneratorOptions(dtoPackages = listOf("com.example.api")),
 * )
 * generator.writeRuntimeTo(File("../web/src/generated/client-runtime.ts"))
 * generator.writeTo(File("../web/src/generated/client.ts"))
 * ```
 */
class ClientGenerator(
    private val options: GeneratorOptions,
    private val formatter: SourceFormatter = SourceFormatter.Passthrough,
    readModel: () -> ApiModel,
) {
    /** The usual entry point: every JAX-RS resource compiled under [classesDir]. */
    constructor(
        classesDir: File,
        options: GeneratorOptions,
        formatter: SourceFormatter = SourceFormatter.Passthrough,
    ) : this(options, formatter, { ApiModelReader(options).read(classesDir) })

    val model: ApiModel by lazy(readModel)

    /** The contract rules, over this generator's model. */
    fun lint(): ApiContractLint = ApiContractLint(model, options)

    /**
     * The shape of the client this generator emits, as canonical text and a hash of it.
     *
     * Available whether or not a stamp is configured, because the hash the generator computes is
     * not the only one worth having: [ApiDigest.contract] is plain text, so a project that wants a
     * different digest — a different algorithm, a subset, folded together with something of its own
     * — has the input rather than only the answer.
     */
    val digest: ApiDigest by lazy { ContractDigest(options).of(model.clientSurface) }

    /**
     * The stamp as [GeneratorOptions.stamp] composes it, or null when no stamp is configured.
     *
     * The same value the client carries, because it is the same call over the same digest — which
     * is what lets the backend compare rather than merely receive.
     */
    val stamp: String? get() = options.stamp?.value?.invoke(digest)

    /** The client as it should be on disk. */
    fun render(): String {
        val covered = model.clientSurface
        check(covered.isNotEmpty()) {
            "none of the ${model.endpoints.size} endpoints read is on a resource carrying @TypeScriptApi, so the " +
                "client would be empty. The annotation is opt-in: mark the resources that belong in the client."
        }
        return formatter.format(TypeScriptClientEmitter(options).emit(covered))
    }

    /** Writes the client, creating the directory if it is not there. Returns the file written. */
    fun writeTo(target: File): File {
        target.parentFile?.mkdirs()
        target.writeText(render())
        return target
    }

    /**
     * Writes the shipped transport next to the client. Call it once when setting a project up, or
     * on every generation to keep it current — it is a static file either way, and never rewritten
     * from the model.
     *
     * Skip it entirely if [GeneratorOptions.runtimeImport] points at a transport you maintain.
     */
    fun writeRuntimeTo(target: File): File {
        target.parentFile?.mkdirs()
        target.writeText(runtimeSource())
        return target
    }

    /**
     * Writes the `Temporal` conversion module next to the client, and returns the file — or null
     * when nothing on the client surface converts to a `Temporal` type, in which case there is no
     * file rather than an empty one.
     *
     * Separate from the client on purpose: naming `Temporal.Instant` in `client.ts` would force
     * `lib: "esnext"` on everything that imports it, and `Temporal` is not yet everywhere
     * (https://caniuse.com/temporal). Importing this module is how a project says it has decided.
     *
     * @param clientImport how this module should import the client, spelled as the statement reads.
     * @param temporalImport where `Temporal` itself comes from. Null uses the global, which
     *   TypeScript declares in its own lib from 7.0 and not before. On an older TypeScript, name a
     *   polyfill — `"@js-temporal/polyfill"` — because the polyfills export `Temporal` as a module
     *   member and declare no global, so `lib: "esnext"` cannot supply it however it is set.
     */
    fun writeTemporalTo(
        target: File,
        clientImport: String = "./client",
        temporalImport: String? = null,
    ): File? {
        val source = temporalSource(clientImport, temporalImport) ?: return null
        target.parentFile?.mkdirs()
        target.writeText(source)
        return target
    }

    /** The `Temporal` module as it should be on disk, or null when there is nothing to convert. */
    fun temporalSource(
        clientImport: String = "./client",
        temporalImport: String? = null,
    ): String? {
        val roots = TypeScriptClientEmitter.rootTypesOf(model.clientSurface)
        val scalars = TypeScriptTypes(options).reachable(roots).namedScalars
        return TemporalModuleEmitter(options).emit(scalars, clientImport, temporalImport)?.let(formatter::format)
    }

    /**
     * Writes [stamp] where the backend can read it, and returns the file — or null when no stamp is
     * configured, in which case there is no file rather than an empty one.
     *
     * This is the half that lets a server answer rather than only be told. The generator writes the
     * value and nothing else: no endpoint, no filter, no format. What the backend does with it — a
     * `GET /api/stamp`, a `ContainerRequestFilter` that logs a request carrying a different one — is
     * the project's code, and the generator has no business guessing at either.
     *
     * A trailing newline is written, so read it with `.trim()`: a one-line file that an editor
     * later ends properly should still compare equal.
     */
    fun writeStampTo(target: File): File? {
        val value = stamp ?: return null
        target.parentFile?.mkdirs()
        target.writeText(value + "\n")
        return target
    }

    /**
     * The first line where [target] and a fresh render disagree, or null when they agree.
     *
     * A whole-file diff is unreadable for a 2000-line generated file, and the first differing line
     * is enough: the fix is always to regenerate.
     */
    fun driftAgainst(target: File): String? {
        if (!target.isFile) return "${target.path} is missing — run the generate task."
        val committed = target.readText()
        val regenerated = render()
        if (committed == regenerated) return null
        val committedLines = committed.lines()
        val regeneratedLines = regenerated.lines()
        val at =
            (0 until maxOf(committedLines.size, regeneratedLines.size))
                .first { committedLines.getOrNull(it) != regeneratedLines.getOrNull(it) }
        return "${target.name} is stale at line ${at + 1}. Regenerate it and commit the result.\n" +
            "  committed:   ${committedLines.getOrNull(at)}\n" +
            "  regenerated: ${regeneratedLines.getOrNull(at)}"
    }

    companion object {
        private const val RUNTIME_RESOURCE = "/dk/lldata/tsclientgen/client-runtime.ts"

        /** The shipped transport, as source. Read it to vendor or extend it. */
        fun runtimeSource(): String =
            ClientGenerator::class.java
                .getResourceAsStream(RUNTIME_RESOURCE)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("$RUNTIME_RESOURCE is missing from the generator jar.")
    }
}
