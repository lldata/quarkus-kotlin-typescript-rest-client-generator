package dk.lldata.tsclientgen.emit

import dk.lldata.tsclientgen.GeneratorOptions
import dk.lldata.tsclientgen.Scalar
import dk.lldata.tsclientgen.model.ApiModelReader
import dk.lldata.tsclientgen.model.Endpoint
import dk.lldata.tsclientgen.model.Param
import dk.lldata.tsclientgen.model.ParamSource
import dk.lldata.typescriptpoet.CodeBlock
import dk.lldata.typescriptpoet.FileSpec
import dk.lldata.typescriptpoet.Modifier
import dk.lldata.typescriptpoet.ObjectLiteral
import dk.lldata.typescriptpoet.TypeName
import dk.lldata.typescriptpoet.TypePredicate
import dk.lldata.typescriptpoet.dsl.argument
import dk.lldata.typescriptpoet.dsl.arrow
import dk.lldata.typescriptpoet.dsl.body
import dk.lldata.typescriptpoet.dsl.call
import dk.lldata.typescriptpoet.dsl.expression
import dk.lldata.typescriptpoet.dsl.extends
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
import dk.lldata.typescriptpoet.dsl.shorthand
import dk.lldata.typescriptpoet.dsl.spread
import dk.lldata.typescriptpoet.dsl.type
import dk.lldata.typescriptpoet.dsl.typeArgument
import dk.lldata.typescriptpoet.dsl.typeParam
import kotlin.reflect.KClass
import kotlin.reflect.KType

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

    /**
     * `namedImport` rather than `implicit`, so Poet collects these into the import statement.
     *
     * The two shapes are imported `typeOnly`, because they are types and two ordinary consumer
     * setups can tell. Under `verbatimModuleSyntax` the import is emitted as written, so a value
     * import of a type is an error (TS1484); under a runtime that *strips* types rather than
     * compiling them — `node --experimental-strip-types` — the binding survives to run time and
     * the module has no such export. Everything else is unaffected: both forms erase to the same
     * JavaScript.
     */
    private val apiConfig = TypeName.namedImport("ApiConfig", options.runtimeImport, typeOnly = true)
    private val requestOptions = TypeName.namedImport("RequestOptions", options.runtimeImport, typeOnly = true)
    private val apiRequest = TypeName.namedImport("apiRequest", options.runtimeImport)

    fun emit(endpoints: List<Endpoint>): String {
        val surface = DeclaredSurface(types, endpoints)
        val reachable = surface.reachable
        val dtos = surface.dtos
        // Composed here rather than handed in, so that anything holding an emitter emits the file
        // its own stamp describes. The digest is over these endpoints, which is what it claims.
        val stamp = options.stamp?.let { it.value(ContractDigest(options).of(endpoints)) }

        for (endpoint in endpoints) refuseUnsupportedParams(endpoint)

        val spec =
            file("client") {
                addComment(options.header)
                // Ahead of every declaration, because it is the one line a consumer's CI check or
                // sync script has to find, and the rest of the file is bulk it scrolls past.
                stamp?.let { stampConstants(it) }
                // First, because everything below may refer to one and nothing here refers back.
                companionInterfaces(reachable.namedScalars.filterIsInstance<Scalar.Branded>())
                for (scalar in reachable.namedScalars) {
                    type(scalar.reference, TypeName.implicit(scalar.declaration!!), Modifier.EXPORT)
                    if (scalar is Scalar.Branded) scalarCompanion(scalar)
                }
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
                        else -> dtoInterface(surface, dto)
                    }
                }
                for (node in ApiNode.of(endpoints, options.rootPath).descendants()) nodeFactory(node)
                options.defaultInstanceName?.let { name ->
                    // No type: `export const api = createApi();` is reachable and inference does
                    // better than `ReturnType<typeof createApi>` did.
                    property(name, Modifier.EXPORT, Modifier.CONST) {
                        initializer("${ApiNode.ROOT_FACTORY}()")
                    }
                }
            }

        val rendered = StringBuilder()
        spec.writeTo(rendered)
        return rendered.toString()
    }

    /**
     * The two shapes a companion can have, declared once so each `const` below can be annotated in
     * a few characters. Poet requires a type on a `const`, and spelling the object type inline
     * would put the longest line in the file next to the shortest value in it.
     */
    private fun FileSpec.Builder.companionInterfaces(branded: List<Scalar.Branded>) {
        if (branded.isEmpty()) return
        val raw = TypeName.typeVariable("Raw")
        val t = TypeName.typeVariable("T")
        interfaze("WireScalar", Modifier.EXPORT) {
            typeParam("Raw")
            typeParam("T", TypeName.bound(raw))
            property("from", TypeName.lambda(mapOf("raw" to raw), t))
        }
        if (branded.any { it.pattern != null }) {
            interfaze("CheckedScalar", Modifier.EXPORT) {
                typeParam("Raw")
                typeParam("T", TypeName.bound(raw))
                extends(TypeName.parameterizedType(TypeName.standard("WireScalar"), raw, t))
                property("is", TypeName.lambda(mapOf("raw" to raw), TypePredicate.isType("raw", t)))
            }
        }
    }

    /**
     * `export const Instant = { from, is }` — the companion a branded scalar is constructed through.
     *
     * A type and a value may share a name in TypeScript, which is as close as the language gets to a
     * Kotlin companion object, and it reads like the library it mirrors: `Instant.from(raw)` beside
     * `Temporal.Instant.from(raw)`.
     *
     * `from` is the cast a caller would otherwise write by hand, with the check they would forget.
     * Where the format is unknown it still brands, and says so rather than pretending to validate.
     */
    private fun FileSpec.Builder.scalarCompanion(scalar: Scalar.Branded) {
        val name = scalar.name
        val raw = scalar.spelling
        val pattern = scalar.pattern
        val companion =
            TypeName.parameterizedType(
                TypeName.standard(if (pattern == null) "WireScalar" else "CheckedScalar"),
                TypeName.implicit(raw),
                TypeName.implicit(name),
            )

        property(name, companion, Modifier.EXPORT, Modifier.CONST) {
            initializer(
                "%L",
                obj {
                    property(
                        "from",
                        arrow("from") {
                            parameter("raw", TypeName.implicit(raw))
                            returns(TypeName.implicit(name))
                            if (pattern == null) {
                                expression("raw as %L", name)
                            } else {
                                // Through its own guard, so the pattern is written once and `from`
                                // needs no cast: past the check, TypeScript has narrowed `raw` to
                                // the brand. Legal to reference here because the const is annotated.
                                body {
                                    statement("if (!%L.is(raw)) throw new TypeError(`invalid %L: \${raw}`)", name, name)
                                    statement("return raw")
                                }
                            }
                        },
                    )
                    // Only where there is something to test. A guard that always returned true
                    // would read as a check and be none.
                    if (pattern != null) {
                        val regex = "/^(?:${pattern.replace("/", "\\/")})$/u"
                        property(
                            "is",
                            arrow("is") {
                                parameter("raw", TypeName.implicit(raw))
                                returnsIs("raw", TypeName.implicit(name))
                                // Built as a call rather than spelled, so the writer can break the
                                // argument list. A regex literal is one token and is often wider
                                // than the print width on its own — this one is 80 characters — so
                                // the line overruns whatever happens. Breaking where a formatter
                                // would break means a project that runs Prettier over its generated
                                // client gets a no-op rather than a reformat, and the drift gate and
                                // the format gate stop disagreeing about the same file.
                                expression(call("$regex.test") { argument("raw") })
                            },
                        )
                    }
                },
            )
        }
    }

    companion object {
        /**
         * Every type the client is built from: what a call answers with, what it sends, and what
         * its path and query parameters are spelled as — an enum or a value class in a query string
         * is named in the call signature, so it has to be declared like any payload type.
         *
         * Here rather than inline, because anything else that has to agree with the emitter about
         * the client's types — the `Temporal` module, most obviously — has to ask the same question,
         * and a second copy of this list is how the two quietly stop agreeing.
         */
        fun rootTypesOf(endpoints: List<Endpoint>): List<KType> =
            endpoints.map { it.responseType }.filterNot { ApiModelReader.isBodyless(it) } +
                endpoints.mapNotNull { it.body?.type } +
                endpoints
                    .flatMap { it.params }
                    .filter { it.source == ParamSource.PATH || it.source == ParamSource.QUERY }
                    .map { it.type }
    }

    private fun variantNames(dto: KClass<*>): Array<TypeName> =
        types
            .unionVariants(dto)
            .map { TypeName.implicit(it.first.simpleName ?: "unknown") }
            .toTypedArray()

    private fun FileSpec.Builder.dtoInterface(
        surface: DeclaredSurface,
        dto: KClass<*>,
    ) = interfaze(dto.simpleName!!, Modifier.EXPORT) {
        for ((name, type, optional) in surface.membersOf(dto)) {
            if (optional) optional(name, type) else property(name, type)
        }
    }

    /**
     * ```ts
     * export const API_STAMP: string = "1.0.0-alpha4.9f3c1a2e";
     * export const API_STAMP_HEADER: string = "x-api-stamp";
     * ```
     *
     * Typed `string` rather than left to infer, which would give each the literal type of its own
     * value — so `serverStamp === API_STAMP` would read as a comparison TypeScript can already
     * settle, and the only use anyone has for these is comparing them with something else.
     *
     * The names are fixed rather than configurable. They are the half of the protocol a consumer
     * and a server agree on without being able to ask, and a project that renamed them would have
     * nothing left to point its CI check at.
     */
    private fun FileSpec.Builder.stampConstants(stamp: String) {
        property("API_STAMP", TypeName.STRING, Modifier.EXPORT, Modifier.CONST) { initializer("%S", stamp) }
        options.stamp?.header?.let { header ->
            property("API_STAMP_HEADER", TypeName.STRING, Modifier.EXPORT, Modifier.CONST) { initializer("%S", header) }
        }
    }

    /**
     * One factory per node. The root's is `createApi`, which is where the caller supplies the config
     * every leaf below it closes over.
     */
    private fun FileSpec.Builder.nodeFactory(node: ApiNode) {
        val isRoot = node.trail.isEmpty()
        val modifiers = if (isRoot) arrayOf(Modifier.EXPORT) else emptyArray()
        // Only the root sends it, and only once: every node below closes over the config it is
        // handed, so folding the header in here puts it on every call without touching a leaf.
        val stampHeader = options.stamp?.header?.takeIf { isRoot }
        function(node.factoryName(), *modifiers) {
            if (isRoot) {
                // Renamed only when there is something to merge, so an unstamped client is
                // byte-identical to what it was. `const config` cannot shadow a parameter of the
                // same name, and `config` is the name every node below already passes along — so
                // it is the parameter that gives way rather than the hundred call sites.
                parameter(if (stampHeader != null) "baseConfig" else "config", apiConfig) { defaultValue("{}") }
            } else {
                parameter("path", TypeName.STRING)
                parameter("config", apiConfig)
            }
            body {
                stampHeader?.let { statement("const config: %T = %L", apiConfig, stampedConfig(it)) }
                statement("return %L", nodeObject(node, isRoot))
            }
        }
    }

    /**
     * ```ts
     * { ...baseConfig, headers: { "x-api-stamp": API_STAMP, ...baseConfig.headers } }
     * ```
     *
     * The caller's own headers spread last, so they win. That is not a preference — the runtime
     * already spreads a per-call `options.headers` over `config.headers`, and a config-level
     * override that lost while a per-call one won would be a rule nobody could keep in their head.
     */
    private fun stampedConfig(header: String): ObjectLiteral =
        obj {
            spread("baseConfig")
            property(
                "headers",
                obj {
                    property(TypeScriptNames.member(header), "%L", "API_STAMP")
                    spread("baseConfig.headers")
                },
            )
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
                    arrow {
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
                                val wire = param.wireName!!
                                property(TypeScriptNames.member(wire), "%L", TypeScriptNames.access(argsAccess, wire))
                            }
                        },
                    )
                }
                if (endpoint.body != null) shorthand("body")
            }

        property(
            verb.lowercase(),
            // The arrow's builder name is a label and never reaches the output — it is attached to
            // an object-literal property, not declared as a named function — so naming it after the
            // verb changes nothing observable. Poet used to refuse `delete` as that name, a reserved
            // word; it no longer does, so there is no reason left to leave it anonymous.
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
                val name = TypeScriptNames.member(param.wireName ?: param.name)
                val type = types.typeOf(param.type)
                // What omitting it means, where the resource says so. Optionality alone reads as
                // "the server copes"; the literal says which answer the caller actually gets.
                // The trailing newline is load-bearing: `emitTSDoc` writes `/**`, the block, then
                // ` */`, so a block that does not end a line puts the closing marker on it.
                val tsDoc = param.defaultValue?.let { CodeBlock.of("Server default: `%L`.\n", it) } ?: CodeBlock.empty()
                if (param.optional) optionalMember(name, type, tsDoc) else member(name, type, tsDoc)
            },
        )

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
