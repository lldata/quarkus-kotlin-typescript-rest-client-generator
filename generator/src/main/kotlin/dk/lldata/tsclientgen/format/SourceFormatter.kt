package dk.lldata.tsclientgen.format

import java.io.File

/**
 * A last pass over the emitted source, so the committed file is laid out like every other file in
 * the consuming repo rather than to Poet's idea of a print width.
 */
fun interface SourceFormatter {
    fun format(source: String): String

    companion object {
        /** Poet's own layout, unchanged. */
        val Passthrough: SourceFormatter = SourceFormatter { it }
    }
}

/**
 * Formats through the consuming repo's own Prettier.
 *
 * Poet gets close on its own but not exact — it leaves a call inline past the print width and, when
 * it does break, breaks only the argument it can. Formatting afterwards is cheaper than chasing
 * that in the emitter, and it is what lets the committed client carry no formatting exemption.
 *
 * Both the writer and the drift check go through the same formatter, so they cannot disagree about
 * what "generated" means. Give the drift check the same instance.
 *
 * @param executable the Prettier binary, usually `node_modules/.bin/prettier`
 * @param filePath passed as `--stdin-filepath`, so Prettier picks the config and parser that apply
 *   to the file being written
 * @param workingDir where Prettier resolves its configuration from
 */
class Prettier(
    private val executable: File,
    private val filePath: String,
    private val workingDir: File = executable.parentFile,
) : SourceFormatter {
    /**
     * Refuses rather than falling back to unformatted output: a drift check comparing two
     * unformatted strings passes while the committed file is wrong, which is the one failure mode a
     * gate must not have.
     */
    override fun format(source: String): String {
        check(executable.canExecute()) {
            "prettier not found at $executable — install the project's node modules. The generated client is " +
                "formatted with the same Prettier the rest of the repo is checked against, so generating " +
                "without it would produce a file that never matches."
        }
        val process =
            ProcessBuilder(executable.absolutePath, "--stdin-filepath", filePath)
                .directory(workingDir)
                .start()
        process.outputStream.bufferedWriter().use { it.write(source) }
        val formatted = process.inputStream.bufferedReader().use { it.readText() }
        val errors = process.errorStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "prettier failed on the generated client:\n$errors" }
        return formatted
    }
}
