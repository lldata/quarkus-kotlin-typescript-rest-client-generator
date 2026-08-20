package dk.lldata.tsclientgen.model

import kotlin.reflect.KParameter
import kotlin.reflect.KType

/**
 * The API as a generator sees it: what the annotated resources declare, with nothing inferred.
 *
 * Read from the compiled classes rather than the source, because that is where the annotations, the
 * resolved types and Kotlin's nullability all agree. Anything this model cannot express is a gap in
 * the contract, not something for an emitter to paper over.
 */
data class ApiModel(
    val endpoints: List<Endpoint>,
) {
    /** The endpoints a client is emitted for — see [Endpoint.inTypeScriptApi]. */
    val clientSurface: List<Endpoint> get() = endpoints.filter { it.inTypeScriptApi }

    /**
     * The model as text, one endpoint per block. The fastest way to see what the emitter has to
     * work with, and what the resources are still failing to declare.
     */
    fun describe(): String =
        buildString {
            appendLine("${endpoints.size} endpoints (${clientSurface.size} on the client surface)")
            for (endpoint in endpoints) {
                appendLine()
                appendLine("${endpoint.verb.padEnd(6)} ${endpoint.path}${if (endpoint.inTypeScriptApi) "" else "   [not in client]"}")
                val wrapper = endpoint.unwrappedFrom?.let { " [via ${it.substringAfterLast('.')}]" }.orEmpty()
                appendLine("       ${endpoint.resource}.${endpoint.function} -> ${endpoint.responseType}$wrapper")
                for (param in endpoint.params) {
                    val wire = param.wireName?.let { " \"$it\"" }.orEmpty()
                    val default = param.defaultValue?.let { " = $it" }.orEmpty()
                    appendLine("         ${param.source.name.lowercase().padEnd(7)}$wire ${param.name}: ${param.type}$default")
                }
            }
        }
}

data class Endpoint(
    val resource: String,
    /** The Kotlin method name. Not emitted, but it is how a violation names itself to a human. */
    val function: String,
    val verb: String,
    val path: String,
    val params: List<Param>,
    val responseType: KType,
    /**
     * The wrapper the declared return type was, when [responseType] is what it was unwrapped from —
     * `org.jboss.resteasy.reactive.RestResponse` for a `RestResponse<BookResponse>`.
     */
    val unwrappedFrom: String?,
    /**
     * True when this endpoint is part of the contract the TypeScript client is generated for —
     * its resource carries `@TypeScriptApi` (while [dk.lldata.tsclientgen.GeneratorOptions.scanForAnnotation]
     * is on), or is named in [dk.lldata.tsclientgen.GeneratorOptions.clientResources], or both.
     *
     * The name is kept from when the annotation was the only route in: renaming it would ripple
     * through [ApiModel], its summary output and its tests for no behavioural gain. Read it as "in
     * the client", not literally as "carries the annotation".
     *
     * Read here rather than in each rule so the lint and the generator cannot disagree about what
     * the client covers.
     */
    val inTypeScriptApi: Boolean,
) {
    val pathParams get() = params.filter { it.source == ParamSource.PATH }
    val queryParams get() = params.filter { it.source == ParamSource.QUERY }
    val body get() = params.firstOrNull { it.source == ParamSource.BODY }

    /** `BooksResource.listBooks` — how an endpoint names itself in an error or a violation. */
    fun id(): String = "$resource.$function"
}

enum class ParamSource {
    PATH,
    QUERY,
    HEADER,
    COOKIE,
    BEAN,
    BODY,
    FORM,
    MATRIX,

    /** `@Context` injection — server-side plumbing, never part of the client contract. */
    CONTEXT,
}

data class Param(
    val name: String,
    /** Wire name for path/query/header/cookie; null for a body or bean param. */
    val wireName: String?,
    val source: ParamSource,
    val type: KType,
    val nullable: Boolean,
    /**
     * The literal from the resource's `@DefaultValue`, so the caller may omit it even when the
     * Kotlin type is not nullable — `limit: Int = 20` is optional on the wire and required in
     * Kotlin — and so the client can say what omitting it means.
     *
     * `@DefaultValue` rather than a Kotlin default parameter value on purpose: reflection can see
     * that a Kotlin default *exists* ([KParameter.isOptional]) but cannot read what it *is*, so a
     * default written that way reaches the client as a shrug. This is the only form that carries.
     */
    val defaultValue: String? = null,
) {
    /** True when the caller may leave it out: nullable, or defaulted by the server. */
    val optional: Boolean get() = nullable || defaultValue != null
}
