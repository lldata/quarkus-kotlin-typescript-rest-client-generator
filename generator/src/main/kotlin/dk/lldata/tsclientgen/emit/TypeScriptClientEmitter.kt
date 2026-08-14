package dk.lldata.tsclientgen.emit

import dk.lldata.tsclientgen.GeneratorOptions
import dk.lldata.tsclientgen.model.ApiModelReader
import dk.lldata.tsclientgen.model.Endpoint
import dk.lldata.tsclientgen.model.Param
import dk.lldata.tsclientgen.model.ParamSource
import dk.lldata.typescriptpoet.CodeBlock
import dk.lldata.typescriptpoet.FileSpec
import dk.lldata.typescriptpoet.Modifier
import dk.lldata.typescriptpoet.ObjectLiteral
import dk.lldata.typescriptpoet.TypeName
import dk.lldata.typescriptpoet.dsl.argument
import dk.lldata.typescriptpoet.dsl.arrow
import dk.lldata.typescriptpoet.dsl.body
import dk.lldata.typescriptpoet.dsl.call
import dk.lldata.typescriptpoet.dsl.expression
import dk.lldata.typescriptpoet.dsl.file
import dk.lldata.typescriptpoet.dsl.function
import dk.lldata.typescriptpoet.dsl.getter
import dk.lldata.typescriptpoet.dsl.interfaze
import dk.lldata.typescriptpoet.dsl.member
import dk.lldata.typescriptpoet.dsl.obj
import dk.lldata.typescriptpoet.dsl.optional
import dk.lldata.typescriptpoet.dsl.optionalMember
import dk.lldata.typescriptpoet.dsl.parameter
import dk.lldata.typescriptpoet.dsl.property
import dk.lldata.typescriptpoet.dsl.returns
import dk.lldata.typescriptpoet.dsl.shorthand
import dk.lldata.typescriptpoet.dsl.type
import dk.lldata.typescriptpoet.dsl.typeArgument
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Writes the TypeScript client for a set of endpoints.
 *
 * The output is data, not craft: it is regenerated and diffed by a drift test, so anything wrong
 * with it is fixed here rather than in the emitted file.
 *
 * Declarations are built with TypeScriptPoet. Everything here is structured rather than
 * hand-formatted text: a node returns an [ObjectLiteral] and each leaf is an arrow
 * [dk.lldata.typescriptpoet.FunctionSpec], so members and parameters stay intact until the file is
 * written and the writer can break a line where it sees a boundary. Final layout is not this file's
 * problem either — a [dk.lldata.tsclientgen.format.SourceFormatter] can run the output through the
 * consuming repo's own formatter.
 */
class TypeScriptClientEmitter(
    private val options: GeneratorOptions,
) {
    private val types = TypeScriptTypes(options)

    /** `namedImport` rather than `implicit`, so Poet collects these into the import statement. */
    private val apiConfig = TypeName.namedImport("ApiConfig", options.runtimeImport)
    private val requestOptions = TypeName.namedImport("RequestOptions", options.runtimeImport)
    private val apiRequest = TypeName.namedImport("apiRequest", options.runtimeImport)

    fun emit(endpoints: List<Endpoint>): String {
        val requestBodyTypes = endpoints.mapNotNull { it.body?.type }
        val responseTypes = endpoints.map { it.responseType }.filterNot { ApiModelReader.isBodyless(it) }
        // Path and query parameters too: an enum or a value class in a query string is spelled by
        // its own name in the call signature, so it has to be declared like any payload type.
        val parameterTypes =
            endpoints
                .flatMap { it.params }
                .filter { it.source == ParamSource.PATH || it.source == ParamSource.QUERY }
                .map { it.type }
        val dtos = types.reachableDtos(responseTypes + requestBodyTypes + parameterTypes)
        val requestDtos = types.reachableDtos(requestBodyTypes).toSet()

        val discriminators = mutableMapOf<KClass<*>, Pair<String, String>>()
        for (dto in dtos.filter { types.isSealedUnion(it) }) {
            val property = types.unionDiscriminatorProperty(dto)
            for ((variant, literal) in types.unionVariants(dto)) {
                discriminators[variant] = property to literal
            }
        }

        for (endpoint in endpoints) refuseUnsupportedParams(endpoint)

        val spec =
            file("client") {
                addComment(options.header)
                for (dto in dtos) {
                    when {
                        // A union of the literals Jackson writes, not an interface: the wire carries
                        // `"borrowed"`, so this has to compare equal to a string.
                        types.isEnum(dto) ->
                            type(dto.simpleName!!, TypeName.unionType(*types.enumWireLiterals(dto).toTypedArray()), Modifier.EXPORT)
                        // `{ value: number }` is the Kotlin declaration; Jackson writes the scalar.
                        types.isValueClass(dto) ->
                            type(dto.simpleName!!, types.typeOf(types.valueClassUnderlyingType(dto)), Modifier.EXPORT)
                        // The base is its variants: emitting the base alone describes none of them.
                        types.isSealedUnion(dto) ->
                            type(dto.simpleName!!, TypeName.unionType(*variantNames(dto)), Modifier.EXPORT)
                        else -> dtoInterface(dto, dto in requestDtos, discriminators[dto])
                    }
                }
                for (node in ApiNode.of(endpoints, options.rootPath).descendants()) nodeFactory(node)
                options.defaultInstanceName?.let { name ->
                    property(name, TypeName.implicit("ReturnType<typeof ${ApiNode.ROOT_FACTORY}>"), Modifier.EXPORT, Modifier.CONST) {
                        initializer("${ApiNode.ROOT_FACTORY}()")
                    }
                }
            }

        val rendered = StringBuilder()
        spec.writeTo(rendered)
        return rendered.toString()
    }

    private fun variantNames(dto: KClass<*>): Array<TypeName> =
        types
            .unionVariants(dto)
            .map { TypeName.implicit(it.first.simpleName ?: "unknown") }
            .toTypedArray()

    private fun FileSpec.Builder.dtoInterface(
        dto: KClass<*>,
        isRequest: Boolean,
        discriminator: Pair<String, String>?,
    ) = interfaze(dto.simpleName!!, Modifier.EXPORT) {
        // Declaration order, not alphabetical: the Kotlin constructor is the readable order.
        val order = dto.primaryConstructorParameterNames()
        for (member in dto.memberProperties.sortedBy { order.indexOf(it.name).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }) {
            val type =
                if (member.name == discriminator?.first) {
                    // The variant fixes this to one literal, which is what makes the union
                    // narrowable: `if (result.outcome === "refused")` tells TypeScript which arm.
                    TypeName.literal(discriminator.second)
                } else {
                    types.nullableTypeOf(member.returnType)
                }
            val isOptional =
                if (isRequest) {
                    types.isOptionalRequestField(dto, member.name)
                } else {
                    types.isOptionalResponseField(dto, member.returnType)
                }
            if (isOptional) optional(member.name, type) else property(member.name, type)
        }
    }

    /**
     * One factory per node. The root's is `createApi`, which is where the caller supplies the config
     * every leaf below it closes over.
     */
    private fun FileSpec.Builder.nodeFactory(node: ApiNode) {
        val isRoot = node.trail.isEmpty()
        val modifiers = if (isRoot) arrayOf(Modifier.EXPORT) else emptyArray()
        function(node.factoryName(), *modifiers) {
            if (isRoot) {
                parameter("config", apiConfig) { defaultValue("{}") }
            } else {
                parameter("path", TypeName.STRING)
                parameter("config", apiConfig)
            }
            body { statement("return %L", nodeObject(node, isRoot)) }
        }
    }

    /** The tree itself: literal segments are getters, so a branch is built only when it is reached. */
    private fun nodeObject(
        node: ApiNode,
        isRoot: Boolean,
    ): ObjectLiteral =
        obj {
            // At the root the path is known, so it is a literal rather than an interpolation of one.
            val prefix = if (isRoot) options.rootPath else "\${path}"
            for ((segment, child) in node.literals) {
                val childPath = if (isRoot) "\"$prefix/$segment\"" else "`$prefix/$segment`"
                getter(ApiNode.camelCase(segment), "return %L", childCall(child, childPath))
            }
            node.dynamic?.let { (param, child) ->
                val name = ApiNode.camelCase(param.wireName ?: param.name)
                val childPath = "`$prefix/\${encodeURIComponent(String($name))}`"
                property(
                    name,
                    arrow(name) {
                        parameter(name, types.typeOf(param.type))
                        expression(childCall(child, childPath))
                    },
                )
            }
            for ((verb, endpoint) in node.operations) operation(verb, endpoint, isRoot)
        }

    private fun childCall(
        child: ApiNode,
        childPath: String,
    ) = call(child.factoryName()) {
        argument("%L", childPath)
        argument("config")
    }

    /**
     * `.get(args, options)` / `.post(body, options)`. Query parameters are named as the wire names
     * them, not as Kotlin does — the client describes the request, and a `limitRaw` parameter is a
     * server-side detail that never reaches it.
     */
    private fun ObjectLiteral.Builder.operation(
        verb: String,
        endpoint: Endpoint,
        isRoot: Boolean,
    ) {
        val returns =
            if (ApiModelReader.isBodyless(endpoint.responseType)) {
                TypeName.VOID
            } else {
                types.typeOf(endpoint.responseType)
            }
        val allOptional = endpoint.queryParams.all { it.optional }
        val argsAccess = if (allOptional) "args?" else "args"

        // Structured rather than a joined string: the writer can only break a line where it can see
        // a member boundary, and a pre-rendered `{ a, b }` is one opaque token to it.
        val request =
            obj {
                property("method", "%S", verb)
                if (isRoot) property("path", "%S", options.rootPath) else shorthand("path")
                if (endpoint.queryParams.isNotEmpty()) {
                    property(
                        "query",
                        obj {
                            for (param in endpoint.queryParams) {
                                property(param.wireName!!, "%L", "$argsAccess.${param.wireName}")
                            }
                        },
                    )
                }
                if (endpoint.body != null) shorthand("body")
            }

        property(
            verb.lowercase(),
            arrow(verb.lowercase()) {
                endpoint.body?.let { parameter("body", types.typeOf(it.type)) }
                if (endpoint.queryParams.isNotEmpty()) {
                    parameter("args", argsObjectType(endpoint), optional = allOptional)
                }
                parameter("options", requestOptions, optional = true)
                returns(TypeName.promiseType(returns))
                // A call built as a list rather than spelled as a format string: the writer can only
                // break where it can see an argument boundary, and `%T<%T>(%L, config, options)` has
                // none in it.
                expression(
                    call(apiRequest) {
                        typeArgument(returns)
                        argument(request)
                        // `config` is its own argument, not a field merged into `options`: the token
                        // belongs to the client instance, and a per-call one would be a second way
                        // in that this would then have to silently override.
                        argument("config")
                        argument("options")
                    },
                )
            },
        )
    }

    /**
     * The inline `{ limit?: number; … }` a call takes its query parameters as.
     *
     * An anonymous type rather than an interface, because each member may carry a doc comment
     * naming the server's default and there is nothing else for the shape to be called.
     */
    private fun argsObjectType(endpoint: Endpoint): TypeName =
        TypeName.anonymousType(
            endpoint.queryParams.map { param ->
                val name = param.wireName ?: param.name
                val type = types.typeOf(param.type)
                // What omitting it means, where the resource says so. Optionality alone reads as
                // "the server copes"; the literal says which answer the caller actually gets.
                val tsDoc = param.defaultValue?.let { CodeBlock.of("Server default: `%L`.", it) } ?: CodeBlock.empty()
                if (param.optional) optionalMember(name, type, tsDoc) else member(name, type, tsDoc)
            },
        )

    private fun KClass<*>.primaryConstructorParameterNames(): List<String> =
        primaryConstructor
            ?.parameters
            ?.mapNotNull { it.name }
            .orEmpty()

    /**
     * Params the client neither sends nor knows about, as opposed to ones the emitter simply cannot
     * express yet. Silently dropping the second kind produces a client that compiles, reads
     * correctly and omits something the endpoint needs — so those still refuse.
     */
    private fun Param.isAmbient(): Boolean =
        when (source) {
            ParamSource.CONTEXT, ParamSource.COOKIE -> true
            ParamSource.HEADER -> wireName?.lowercase() in options.ambientHeaders
            else -> false
        }

    private fun refuseUnsupportedParams(endpoint: Endpoint) {
        val unsupported =
            endpoint.params.filterNot {
                it.source in setOf(ParamSource.PATH, ParamSource.QUERY, ParamSource.BODY) || it.isAmbient()
            }
        check(unsupported.isEmpty()) {
            "${endpoint.id()} takes " +
                unsupported.joinToString(", ") { "${it.source.name.lowercase()} '${it.wireName ?: it.name}'" } +
                ", which the emitter cannot express yet. Teach it that param source, or classify it " +
                "as ambient, before covering this endpoint."
        }
    }
}
