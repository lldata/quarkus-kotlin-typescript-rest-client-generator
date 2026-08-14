package dk.lldata.tsclientgen.lint

import dk.lldata.tsclientgen.GeneratorOptions
import dk.lldata.tsclientgen.TypeScriptValueObject
import dk.lldata.tsclientgen.emit.ApiNode
import dk.lldata.tsclientgen.emit.TypeScriptTypes
import dk.lldata.tsclientgen.model.ApiModel
import dk.lldata.tsclientgen.model.ApiModelReader
import dk.lldata.tsclientgen.model.Endpoint
import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.superclasses

/**
 * The properties the generator depends on, asserted over the API model rather than re-derived by
 * each emitter.
 *
 * This is where "we decided every endpoint declares X" becomes enforceable. Run it from a test in
 * the consuming project: a resource that breaks one of these produces a client that compiles and
 * misleads, which is the failure a generated client exists to prevent.
 *
 * Every rule reports rather than throws, so a project adopting the generator can start with the
 * violations it has, filter the rules it is not ready for, and watch the list only shrink.
 */
class ApiContractLint(
    private val model: ApiModel,
    private val options: GeneratorOptions,
) {
    private val types = TypeScriptTypes(options)

    /**
     * The endpoints a TypeScript client is emitted for.
     *
     * Scope is per rule, not per suite, because the rules do not all answer to the same authority.
     * A rule whose reason is "this name becomes an exported TypeScript symbol" has nothing to say
     * about an endpoint that is never emitted, and enforcing it there would mean renaming internal
     * types to satisfy a constraint that does not reach them. [ApiContractRule.GET_OR_POST_ONLY] is
     * about the HTTP API itself and reads every endpoint.
     */
    private val clientSurface get() = model.clientSurface

    fun violations(): List<ApiContractViolation> =
        buildList {
            addAll(typedRequestBodies())
            addAll(topLevelResponseTypes())
            addAll(responseNaming())
            addAll(sealedSubtypesComplete())
            addAll(variantLiteralsInDiscriminator())
            addAll(getOrPostOnly())
            addAll(usablePathSegments())
            addAll(convertibleEnumParams())
        }

    /** Throws with the whole list when anything is wrong. The one-liner for a test to call. */
    fun check(rules: Set<ApiContractRule> = ApiContractRule.entries.toSet()) {
        val found = violations().filter { it.rule in rules }
        check(found.isEmpty()) {
            "The API contract has ${found.size} violation(s):\n" +
                found.joinToString("\n") { "  [${it.rule}] ${it.subject}: ${it.message}" }
        }
    }

    /**
     * A generator reading `body: String` can only emit `string`, which is honest and useless: the
     * caller gets no shape and the resource parses by hand, with its own bespoke 400s.
     */
    private fun typedRequestBodies(): List<ApiContractViolation> =
        clientSurface
            .filter { it.body?.type?.classifier == String::class }
            .map {
                ApiContractViolation(
                    ApiContractRule.TYPED_REQUEST_BODY,
                    it.id(),
                    "takes its request body as a String, so the client can only be told `string`. Declare a DTO.",
                )
            }

    /**
     * A nested class has no natural flat TypeScript name, and the emitted symbol is user-visible.
     *
     * Looks through `List<T>` because the emitter does: a nested DTO inside a collection needs a
     * name just as much as one returned directly.
     */
    private fun topLevelResponseTypes(): List<ApiContractViolation> =
        clientSurface
            .flatMap { endpoint -> endpoint.responseDtos().filter { it.java.enclosingClass != null }.map { endpoint to it } }
            .distinctBy { (endpoint, klass) -> endpoint.id() to klass.qualifiedName }
            .map { (endpoint, klass) ->
                ApiContractViolation(
                    ApiContractRule.TOP_LEVEL_RESPONSE_TYPE,
                    endpoint.id(),
                    "reaches ${klass.qualifiedName}, which is nested in ${klass.java.enclosingClass.simpleName} and has no flat " +
                        "TypeScript name. Lift it to a top-level class.",
                )
            }

    /**
     * These names become the exported TypeScript symbols, so an inconsistency that never mattered
     * internally becomes part of the client's public surface.
     *
     * A type whose only role is being one endpoint's response is named `*Response`. A value object
     * shared across calls is not that, so it carries `@TypeScriptValueObject` and keeps its own
     * name — exempt by construction rather than by a list somebody maintains.
     *
     * Enums and value classes are exempt by kind: neither is a response, and both are emitted as
     * scalars rather than named object shapes. A sealed variant inherits its base's decision — it
     * is emitted as an arm of that union, never named on its own.
     */
    private fun responseNaming(): List<ApiContractViolation> =
        clientSurface
            .flatMap { it.responseDtos() }
            .distinct()
            .filterNot { types.isEnum(it) || types.isValueClass(it) }
            .filterNot { klass -> klass.superclasses.any { it.isSealed } }
            .filterNot { it.java.isAnnotationPresent(TypeScriptValueObject::class.java) }
            .filterNot { it.simpleName?.endsWith("Response") == true }
            .map {
                ApiContractViolation(
                    ApiContractRule.RESPONSE_NAMING,
                    it.simpleName ?: it.toString(),
                    "is emitted into the client but is named neither `*Response` nor marked @TypeScriptValueObject. " +
                        "Rename it, or say it is shared vocabulary.",
                )
            }

    /**
     * A sealed type reaches the client as a discriminated union, and the generator learns the arms
     * from `@JsonSubTypes` — reflection can list [KClass.sealedSubclasses] but cannot read which
     * literal a variant fixes its discriminator to without an instance.
     *
     * So the annotation is a list, and this is what stops it being one that drifts: adding a
     * variant without mapping it fails here rather than silently dropping an arm from the client.
     */
    private fun sealedSubtypesComplete(): List<ApiContractViolation> =
        unions().mapNotNull { union ->
            val mapped = types.unionVariants(union).map { it.first }.toSet()
            val declared = union.sealedSubclasses.toSet()
            if (mapped == declared) {
                return@mapNotNull null
            }
            val missing = (declared - mapped).mapNotNull { it.simpleName }
            val extra = (mapped - declared).mapNotNull { it.simpleName }
            ApiContractViolation(
                ApiContractRule.SEALED_SUBTYPES_COMPLETE,
                union.simpleName ?: union.toString(),
                "maps variants that are not exactly its subclasses" +
                    (if (missing.isEmpty()) "" else "; unmapped: ${missing.joinToString()}") +
                    (if (extra.isEmpty()) "" else "; mapped but not a subclass: ${extra.joinToString()}"),
            )
        }

    /**
     * The literal in `@JsonSubTypes` is what the client switches on, so it has to be a value the
     * discriminator can actually hold — a mapping that merely looks right would put a value in the
     * client that no response ever carries.
     *
     * Only checkable when the discriminator is an enum. A `String` discriminator has no declared
     * set of values to compare against, and this rule says nothing about it.
     */
    private fun variantLiteralsInDiscriminator(): List<ApiContractViolation> =
        unions().flatMap { union ->
            val discriminator = types.unionDiscriminatorProperty(union)
            val declaring =
                union.memberProperties.firstOrNull { it.name == discriminator }
                    ?: return@flatMap listOf(
                        ApiContractViolation(
                            ApiContractRule.VARIANT_LITERAL_IN_DISCRIMINATOR,
                            union.simpleName ?: union.toString(),
                            "declares @JsonTypeInfo(property = \"$discriminator\") but has no such property, so the " +
                                "variants have nothing to write it into.",
                        ),
                    )
            val enumType = declaring.returnType.classifier as? KClass<*> ?: return@flatMap emptyList()
            if (!types.isEnum(enumType)) return@flatMap emptyList()
            val allowed = types.enumWireLiterals(enumType).map { it.toString().trim('"') }.toSet()
            types
                .unionVariants(union)
                .filterNot { it.second in allowed }
                .map { (variant, literal) ->
                    ApiContractViolation(
                        ApiContractRule.VARIANT_LITERAL_IN_DISCRIMINATOR,
                        "${union.simpleName}.${variant.simpleName}",
                        "is mapped to \"$literal\", which ${enumType.simpleName} cannot hold (${allowed.joinToString()}).",
                    )
                }
        }

    /**
     * Two verbs, not four — an opinion, and one worth having: `GET` and `POST` cannot express what
     * an application does, so the action goes in the last path segment instead of into a verb, and
     * the client reads `books.isbn(x).markLost.post()` rather than needing a rule for which of
     * `PUT`/`PATCH`/`DELETE` a given change is.
     *
     * Every endpoint, not just the client surface: this is about the HTTP API rather than the
     * generated client. Filter this rule out if your project decided otherwise — the generator
     * itself emits any verb.
     */
    private fun getOrPostOnly(): List<ApiContractViolation> =
        model.endpoints
            .filterNot { it.verb == "GET" || it.verb == "POST" }
            .map {
                ApiContractViolation(
                    ApiContractRule.GET_OR_POST_ONLY,
                    "${it.verb} ${it.path}",
                    "uses a verb outside GET/POST. Put the action in the path instead.",
                )
            }

    /**
     * Segment names become the client's properties and methods, and the factory names derived from
     * them have to be distinct or one node would shadow another.
     *
     * A reserved word is fine as a *property* — a `return` segment emits `get return()`, which is
     * legal — so only the factory names, which are real function declarations, are checked against
     * the reserved set.
     */
    private fun usablePathSegments(): List<ApiContractViolation> {
        if (clientSurface.isEmpty()) return emptyList()
        val nodes = ApiNode.of(clientSurface, options.rootPath).descendants()
        val identifier = Regex("[a-zA-Z_$][a-zA-Z0-9_$]*")

        val duplicated =
            nodes
                .map { it.factoryName() }
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
                .map {
                    ApiContractViolation(
                        ApiContractRule.USABLE_PATH_SEGMENTS,
                        it,
                        "is the factory name of two different nodes, so one would shadow the other.",
                    )
                }
        val unusable =
            nodes
                .flatMap { node -> node.literals.keys.map { ApiNode.camelCase(it) } + listOfNotNull(node.dynamic?.param?.wireName) }
                .filterNot { it.matches(identifier) }
                .map {
                    ApiContractViolation(
                        ApiContractRule.USABLE_PATH_SEGMENTS,
                        it,
                        "is not a usable TypeScript member name.",
                    )
                }
        val reserved =
            nodes
                .map { it.factoryName() }
                .filter { it in TYPESCRIPT_RESERVED }
                .map {
                    ApiContractViolation(
                        ApiContractRule.USABLE_PATH_SEGMENTS,
                        it,
                        "is a reserved word and cannot be a function declaration.",
                    )
                }
        return duplicated + unusable + reserved
    }

    /**
     * An enum whose `@JsonValue` differs from its constant name, used in a path or a query string,
     * needs a static `fromString`.
     *
     * `@JsonValue` governs the JSON body and nothing else: JAX-RS converts a path or query
     * parameter with `valueOf`, which knows `WORN` and not `worn`. The generated client sends what
     * Jackson writes — the client's type is that literal union — so without `fromString` the call
     * type-checks, reads correctly, and 404s. `fromString` is the conversion hook JAX-RS prefers
     * for an enum; a `ParamConverterProvider` is the other answer, and this rule cannot see one.
     */
    private fun convertibleEnumParams(): List<ApiContractViolation> =
        clientSurface
            .flatMap { endpoint -> endpoint.pathParams + endpoint.queryParams }
            .mapNotNull { it.type.classifier as? KClass<*> }
            .filter { types.isEnum(it) }
            .distinct()
            .filter { enumType -> types.enumWireLiterals(enumType).map { it.toString().trim('"') } != enumType.constantNames() }
            .filterNot { it.hasStaticFromString() }
            .map {
                ApiContractViolation(
                    ApiContractRule.ENUM_QUERY_PARAM_CONVERTIBLE,
                    it.simpleName ?: it.toString(),
                    "is a path or query parameter whose @JsonValue spelling is not its constant name, and it has no " +
                        "static fromString(String). The client sends the @JsonValue spelling, which JAX-RS cannot convert.",
                )
            }

    private fun KClass<*>.constantNames(): List<String> = java.enumConstants.orEmpty().map { (it as Enum<*>).name }

    private fun KClass<*>.hasStaticFromString(): Boolean =
        java.methods.any {
            it.name == "fromString" &&
                Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == String::class.java
        }

    private fun unions(): List<KClass<*>> =
        clientSurface
            .flatMap { it.responseDtos() }
            .filter { types.isSealedUnion(it) }
            .distinct()

    /**
     * Every payload type a response is built from — the same set the emitter declares, because it is
     * the emitter's own walk.
     *
     * Deriving it here rather than repeating the traversal is the point: a hand-rolled version that
     * looked only through collections misses everything nested one property deep, and those types
     * are just as much part of the published surface.
     */
    private fun Endpoint.responseDtos(): List<KClass<*>> {
        if (ApiModelReader.isBodyless(responseType)) return emptyList()
        return types.reachableDtos(listOf(responseType))
    }

    private companion object {
        /** Enough of the reserved set to catch a plausible path segment. */
        val TYPESCRIPT_RESERVED =
            setOf(
                "await",
                "break",
                "case",
                "catch",
                "class",
                "const",
                "continue",
                "debugger",
                "default",
                "delete",
                "do",
                "else",
                "enum",
                "export",
                "extends",
                "false",
                "finally",
                "for",
                "function",
                "if",
                "import",
                "in",
                "instanceof",
                "new",
                "null",
                "return",
                "super",
                "switch",
                "this",
                "throw",
                "true",
                "try",
                "typeof",
                "var",
                "void",
                "while",
                "with",
                "yield",
            )
    }
}

enum class ApiContractRule {
    TYPED_REQUEST_BODY,
    TOP_LEVEL_RESPONSE_TYPE,
    RESPONSE_NAMING,
    SEALED_SUBTYPES_COMPLETE,
    VARIANT_LITERAL_IN_DISCRIMINATOR,
    GET_OR_POST_ONLY,
    USABLE_PATH_SEGMENTS,
    ENUM_QUERY_PARAM_CONVERTIBLE,
}

data class ApiContractViolation(
    val rule: ApiContractRule,
    /** What broke the rule — an endpoint id, a type name, a path segment. */
    val subject: String,
    val message: String,
) {
    override fun toString(): String = "[$rule] $subject: $message"
}
