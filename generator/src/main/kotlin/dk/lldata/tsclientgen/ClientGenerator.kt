package dk.lldata.tsclientgen

import dk.lldata.tsclientgen.emit.TypeScriptClientEmitter
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
    private val readModel: () -> ApiModel,
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
