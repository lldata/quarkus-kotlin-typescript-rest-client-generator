package dk.lldata.tsclientgen.emit

import dk.lldata.tsclientgen.GeneratorOptions
import dk.lldata.tsclientgen.Scalar
import dk.lldata.typescriptpoet.Modifier
import dk.lldata.typescriptpoet.TypeName
import dk.lldata.typescriptpoet.dsl.arrow
import dk.lldata.typescriptpoet.dsl.expression
import dk.lldata.typescriptpoet.dsl.file
import dk.lldata.typescriptpoet.dsl.obj
import dk.lldata.typescriptpoet.dsl.parameter
import dk.lldata.typescriptpoet.dsl.property
import dk.lldata.typescriptpoet.dsl.returns

/**
 * A module converting the client's date scalars to and from `Temporal`, emitted separately from the
 * client and imported only by the files that want it.
 *
 * Separate for two reasons that both survive `Temporal` being everywhere. The typecheck is the
 * binding one: naming `Temporal.Instant` in `client.ts` would force `lib: "esnext"` on every project
 * that imports the client. The runtime is narrower but real — a module that only *defines*
 * `Temporal.Instant.from(v)` does not touch `Temporal` until called, so importing it is safe, but a
 * server-rendered path that calls one under a Node without `--harmony-temporal` fails at request
 * time rather than at build time, which is the failure this project exists to move earlier.
 *
 * The conversions are uniform because ISO-8601 is what both sides speak: `Temporal.X.from(string)`
 * one way, `toString()` the other. That is the whole reason this is cheap — the wire format the
 * server already writes is the interchange format `Temporal` already parses.
 */
class TemporalModuleEmitter(
    private val options: GeneratorOptions,
) {
    /**
     * The module, or null when nothing the client uses converts to a `Temporal` type — in which case
     * there is no file to write rather than an empty one to explain.
     */
    fun emit(
        scalars: List<Scalar>,
        clientImport: String,
    ): String? {
        val convertible = scalars.filter { it.temporal != null }.distinctBy { it.reference }
        if (convertible.isEmpty()) return null

        // `namedImport` rather than a hand-written statement, so Poet collects these into one
        // import at the top of the file. `addCode` appends at the end, where an import is legal and
        // reads like a mistake.
        val imported = convertible.associateWith { TypeName.namedImport(it.reference, clientImport) }

        val spec =
            file("client-temporal") {
                addComment(options.header)
                property("toTemporal", TypeName.implicit(toTemporalType(convertible)), Modifier.EXPORT, Modifier.CONST) {
                    initializer(
                        "%L",
                        obj {
                            for (scalar in convertible) {
                                property(
                                    memberName(scalar),
                                    arrow(memberName(scalar)) {
                                        parameter("value", imported.getValue(scalar))
                                        returns(TypeName.implicit(scalar.temporal!!))
                                        expression("%L.from(value)", scalar.temporal!!)
                                    },
                                )
                            }
                        },
                    )
                }
                property("fromTemporal", TypeName.implicit(fromTemporalType(convertible)), Modifier.EXPORT, Modifier.CONST) {
                    initializer(
                        "%L",
                        obj {
                            for (scalar in convertible) {
                                property(
                                    memberName(scalar),
                                    arrow(memberName(scalar)) {
                                        parameter("value", TypeName.implicit(scalar.temporal!!))
                                        returns(imported.getValue(scalar))
                                        // A branded scalar has a companion, so the wire-bound
                                        // direction goes through it and is validated on the way in.
                                        // A plain alias has nothing to call, so it is a cast.
                                        if (scalar is Scalar.Branded) {
                                            expression("%T.from(value.toString())", imported.getValue(scalar))
                                        } else {
                                            expression("value.toString() as %T", imported.getValue(scalar))
                                        }
                                    },
                                )
                            }
                        },
                    )
                }
            }

        val rendered = StringBuilder()
        spec.writeTo(rendered)
        return rendered.toString()
    }

    /** `instant`, `localDate` — the alias, lowercased at the front, since it is a member now. */
    private fun memberName(scalar: Scalar): String = scalar.reference.replaceFirstChar { it.lowercase() }

    private fun toTemporalType(scalars: List<Scalar>): String =
        scalars.joinToString("; ", "{ ", " }") { "readonly ${memberName(it)}: (value: ${it.reference}) => ${it.temporal}" }

    private fun fromTemporalType(scalars: List<Scalar>): String =
        scalars.joinToString("; ", "{ ", " }") { "readonly ${memberName(it)}: (value: ${it.temporal}) => ${it.reference}" }
}
