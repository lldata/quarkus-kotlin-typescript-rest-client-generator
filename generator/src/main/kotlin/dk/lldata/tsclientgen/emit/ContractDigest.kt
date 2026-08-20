package dk.lldata.tsclientgen.emit

import dk.lldata.tsclientgen.ApiDigest
import dk.lldata.tsclientgen.GeneratorOptions
import dk.lldata.tsclientgen.Scalar
import dk.lldata.tsclientgen.model.ApiModelReader
import dk.lldata.tsclientgen.model.Endpoint
import dk.lldata.tsclientgen.model.ParamSource
import kotlin.reflect.KClass

/**
 * The emitted contract as canonical text, and the hash of it.
 *
 * Over the contract rather than the emitted bytes, so the hash reports incompatibility rather than
 * churn: a doc comment, a layout decision, or a rename that never reaches the wire leaves it where
 * it was. What does move it is anything a consumer writes code against — an endpoint, a wire name,
 * a type, whether a field may be absent, how a scalar is spelled.
 *
 * Two things are deliberately left out. [GeneratorOptions.header] is prose. [GeneratorOptions
 * .runtimeImport] is the consuming project's own resolution concern, and a vendored copy commonly
 * rewrites that line on the way in — hashing it would report every such copy as different from the
 * client it actually is.
 *
 * Everything is sorted rather than emitted in traversal order, so the hash is reproducible across
 * machines and does not move when a resource is reordered. Parameters keep declaration order,
 * because that *is* the emitted call signature.
 */
internal class ContractDigest(
    private val options: GeneratorOptions,
) {
    private val types = TypeScriptTypes(options)

    fun of(endpoints: List<Endpoint>): ApiDigest = ApiDigest.of(describe(endpoints))

    private fun describe(endpoints: List<Endpoint>): String {
        val surface = DeclaredSurface(types, endpoints)
        return buildString {
            appendLine("root ${options.rootPath}")
            options.defaultInstanceName?.let { appendLine("instance $it") }

            for (endpoint in endpoints.sortedWith(compareBy({ it.path }, { it.verb }))) {
                appendLine()
                appendLine("${endpoint.verb} ${endpoint.path}")
                for (param in endpoint.params) {
                    when (param.source) {
                        ParamSource.PATH, ParamSource.QUERY ->
                            appendLine(
                                "  ${param.source.name.lowercase()} ${param.wireName ?: param.name}" +
                                    "${if (param.optional) "?" else ""}: ${types.renderNullable(param.type)}",
                            )
                        ParamSource.BODY -> appendLine("  body: ${types.render(param.type)}")
                        // Everything else is either ambient or refused by the emitter, and neither
                        // reaches the client — so neither is part of what a consumer depends on.
                        else -> Unit
                    }
                }
                appendLine("  -> ${responseOf(endpoint)}")
            }

            for (scalar in surface.reachable.namedScalars.sortedBy { it.reference }) {
                appendLine()
                appendLine("scalar ${scalar.reference} = ${shapeOf(scalar)}")
            }

            for (dto in surface.dtos.sortedBy { it.simpleName }) {
                appendLine()
                append(declarationOf(surface, dto))
            }
        }
    }

    private fun responseOf(endpoint: Endpoint): String =
        if (ApiModelReader.isBodyless(endpoint.responseType)) "void" else types.render(endpoint.responseType)

    /**
     * The pattern is in here because it is emitted: a branded scalar's `is` guard runs it, so
     * loosening one changes what the client accepts at run time.
     */
    private fun shapeOf(scalar: Scalar): String =
        when (scalar) {
            is Scalar.Branded -> "branded ${scalar.spelling}" + (scalar.pattern?.let { " /$it/" } ?: "")
            else -> scalar.spelling
        }

    private fun declarationOf(
        surface: DeclaredSurface,
        dto: KClass<*>,
    ): String {
        val name = dto.simpleName!!
        return when {
            types.isEnum(dto) -> "type $name = ${types.enumWireLiterals(dto).joinToString(" | ")}\n"
            types.isValueClass(dto) -> "type $name = ${types.render(types.valueClassUnderlyingType(dto))}\n"
            types.isSealedUnion(dto) ->
                "type $name = ${types.unionVariants(dto).joinToString(" | ") { it.first.simpleName ?: "unknown" }}\n"
            else ->
                buildString {
                    appendLine("type $name")
                    for ((memberName, type, optional) in surface.membersOf(dto)) {
                        appendLine("  $memberName${if (optional) "?" else ""}: $type")
                    }
                }
        }
    }
}
