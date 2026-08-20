package dk.lldata.tsclientgen.emit

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue
import dk.lldata.tsclientgen.GeneratorOptions
import dk.lldata.tsclientgen.Scalar
import dk.lldata.tsclientgen.UntypedPolicy
import dk.lldata.typescriptpoet.TypeName
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.starProjectedType
import kotlin.reflect.jvm.javaField

/**
 * How a Kotlin type on the wire is spelled in TypeScript, and which fields may be absent.
 *
 * The optionality rules are the part worth getting right, because both directions are easy to get
 * plausibly wrong — and a plausibly wrong generated type compiles at every call site while reading
 * `undefined` at runtime.
 */
class TypeScriptTypes(
    private val options: GeneratorOptions,
) {
    private companion object {
        /** Matched by name: it lives in jackson-databind, which this library does not depend on. */
        const val JSON_NAMING = "com.fasterxml.jackson.databind.annotation.JsonNaming"

        /**
         * Return types that hold a body without declaring its type, and what to do instead.
         *
         * `jakarta.ws.rs.core.Response` is the idiomatic plain-JAX-RS shape and the one that makes
         * a Jersey or Jakarta EE codebase harder to generate from than a Quarkus one — which is not
         * a Quarkus feature so much as what `RestResponse<T>` was added to fix.
         */
        val PAYLOAD_ERASING =
            mapOf(
                "jakarta.ws.rs.core.Response" to
                    "Declare the payload as the return type and let the framework set the status, or return a typed " +
                    "envelope the generator can unwrap — `RestResponse<T>` and `Uni<T>` are unwrapped out of the box, " +
                    "and GeneratorOptions.wrapperTypes takes more. If the endpoint genuinely has no typed body, return " +
                    "`Unit` for a bodyless one, or leave its resource off @TypeScriptApi.",
                "jakarta.ws.rs.core.StreamingOutput" to
                    "A stream has no JSON shape. Leave this endpoint's resource off @TypeScriptApi and call it directly.",
            )

        /** Declares a type, and that type says nothing. `JsonNode` is excluded on purpose. */
        val OPAQUE_PAYLOAD = setOf("kotlin.Any", "java.lang.Object")
    }

    /**
     * Why [type] cannot be described to a client, or null when it can.
     *
     * Two shapes qualify. One holds a body without declaring what is in it —
     * `jakarta.ws.rs.core.Response`, the idiomatic plain-JAX-RS return. The other declares a type
     * that says nothing — a bare `Any`, which reaches the client as `unknown`.
     *
     * Public because the emitter and [dk.lldata.tsclientgen.lint.ApiContractLint] must agree about
     * it: the emitter refuses one endpoint at a time, which is no way to find out that fifty of
     * them are like this, so the lint reports the whole set before anyone tries to generate.
     *
     * `JsonNode` is deliberately not here. Arbitrary JSON is a real thing for an endpoint to
     * return, `unknown` describes it accurately, and the caller has to narrow it either way.
     */
    fun untypedPayload(type: KType): String? {
        val name = (type.classifier as? KClass<*>)?.qualifiedName ?: return null
        PAYLOAD_ERASING[name]?.let {
            return "`$name` does not say what it carries, so the generator cannot tell the client what the call " +
                "returns. $it"
        }
        return if (name in OPAQUE_PAYLOAD) {
            "`$name` reaches the client as `unknown`, which tells a caller nothing. Declare the shape it " +
                "actually carries, or use a `JsonNode` to say the JSON is genuinely arbitrary."
        } else {
            null
        }
    }

    /** A DTO the emitter has to declare, as opposed to something with a TypeScript spelling already. */
    fun isDto(type: KType): Boolean {
        val klass = type.classifier as? KClass<*> ?: return false
        val name = klass.qualifiedName ?: return false
        if (options.scalars.containsKey(name)) return false
        if (isCollection(klass) || isMap(klass) || isOpaqueJson(klass)) return false
        return options.dtoPackages.any { name == it || name.startsWith("$it.") }
    }

    /**
     * True for a Kotlin/Java enum. Jackson writes one as a **scalar** — a string, or whatever
     * `@JsonValue` returns — so reflecting `name`/`ordinal`/backing fields into an object type
     * would describe the JVM's view of the constant rather than anything that reaches the wire.
     */
    fun isEnum(klass: KClass<*>): Boolean = klass.java.isEnum

    /** True for a sealed hierarchy — on the wire a union, not the base's own members. */
    fun isSealedUnion(klass: KClass<*>): Boolean = klass.isSealed

    /**
     * The property carrying the discriminator, from `@JsonTypeInfo`.
     *
     * Refuses an unmapped sealed type rather than falling back to the base's own members: that
     * fallback emits `{ ok, type }` with none of the variants' fields, which compiles at every call
     * site and carries nothing.
     */
    fun unionDiscriminatorProperty(klass: KClass<*>): String {
        val typeInfo =
            klass.java.getAnnotation(JsonTypeInfo::class.java)
                ?: error(
                    "${klass.qualifiedName} is a sealed type on the client surface with no @JsonTypeInfo. " +
                        "The generator cannot tell which property discriminates it, and emitting only the " +
                        "base's members would describe none of the variants.",
                )
        check(typeInfo.include == JsonTypeInfo.As.EXISTING_PROPERTY) {
            "${klass.qualifiedName} must use JsonTypeInfo.As.EXISTING_PROPERTY so the variants keep writing " +
                "the discriminator themselves and the wire is unchanged."
        }
        return typeInfo.property.ifEmpty { error("${klass.qualifiedName} declares @JsonTypeInfo with no property name.") }
    }

    /**
     * Each variant and the literal its discriminator holds, from `@JsonSubTypes`.
     *
     * The literal has to come from a declaration: reflection can enumerate [KClass.sealedSubclasses]
     * but cannot read a property initializer without an instance, and a variant typically fixes its
     * discriminator in the class body.
     */
    fun unionVariants(klass: KClass<*>): List<Pair<KClass<*>, String>> {
        val subTypes =
            klass.java.getAnnotation(JsonSubTypes::class.java)
                ?: error("${klass.qualifiedName} has @JsonTypeInfo but no @JsonSubTypes, so its variants cannot be named.")
        return subTypes.value.map { subType ->
            val name = subType.name.ifEmpty { error("${klass.qualifiedName} maps ${subType.value.qualifiedName} with no name.") }
            subType.value to name
        }
    }

    /**
     * True for a `@JvmInline value class`. Jackson unwraps one to the value it wraps — `"ubud"`,
     * `-8.5` — so it is a scalar on the wire for the same reason an enum is, and reflecting its
     * single property into `{ value: T }` describes the Kotlin declaration instead of the JSON.
     */
    fun isValueClass(klass: KClass<*>): Boolean = klass.isValue

    /** The type a value class collapses to on the wire. */
    fun valueClassUnderlyingType(klass: KClass<*>): KType =
        klass.primaryConstructor
            ?.parameters
            ?.singleOrNull()
            ?.type
            ?: error("${klass.qualifiedName} is a value class without a single constructor parameter — nothing to unwrap to.")

    /**
     * The literals Jackson writes for [klass]'s constants, already spelled as TypeScript.
     *
     * `@JsonValue` wins when present — on a property or on a no-argument method, both of which are
     * ordinary ways to write one. Without one, Jackson writes the constant name.
     */
    fun enumWireLiterals(klass: KClass<*>): List<TypeName> {
        val constants = klass.java.enumConstants
        check(!constants.isNullOrEmpty()) { "${klass.qualifiedName} is an enum with no constants — nothing can be spelled for it." }
        val readJsonValue = jsonValueReader(klass)
        return constants.map { constant ->
            literalOf(klass, readJsonValue?.invoke(constant) ?: (constant as Enum<*>).name)
        }
    }

    /**
     * Refuses rather than guesses: a `@JsonValue` returning something that is not a scalar has no
     * honest TypeScript literal, and emitting a plausible one is exactly the failure this pipeline
     * exists to prevent.
     */
    private fun literalOf(
        klass: KClass<*>,
        raw: Any?,
    ): TypeName =
        when (raw) {
            is String -> TypeName.literal(raw)
            is Int -> TypeName.literal(raw)
            is Long -> TypeName.literal(raw)
            is Boolean -> TypeName.literal(raw)
            else ->
                error(
                    "${klass.qualifiedName} has a @JsonValue of type ${raw?.let { it::class.qualifiedName }}, " +
                        "which has no TypeScript literal spelling. Return a string or a number, or give the " +
                        "generator a rule for this type.",
                )
        }

    private fun jsonValueReader(klass: KClass<*>): ((Any) -> Any?)? {
        klass.java.declaredFields
            .firstOrNull { it.isAnnotationPresent(JsonValue::class.java) }
            ?.let { field ->
                field.isAccessible = true
                return { constant -> field.get(constant) }
            }
        klass.java.declaredMethods
            .firstOrNull { it.isAnnotationPresent(JsonValue::class.java) && it.parameterCount == 0 }
            ?.let { method ->
                method.isAccessible = true
                return { constant -> method.invoke(constant) }
            }
        return null
    }

    private fun isCollection(klass: KClass<*>): Boolean =
        klass.qualifiedName in setOf("kotlin.collections.List", "kotlin.collections.Set", "kotlin.collections.Collection", "kotlin.Array")

    private fun isMap(klass: KClass<*>): Boolean = klass.qualifiedName == "kotlin.collections.Map"

    /**
     * Jackson's `JsonNode` is an untyped blob on the wire. `unknown` says exactly that and forces
     * the caller to narrow before using it — `any` would let it pass silently as anything.
     */
    private fun isOpaqueJson(klass: KClass<*>): Boolean =
        klass.qualifiedName == "com.fasterxml.jackson.databind.JsonNode" || klass.qualifiedName == "kotlin.Any"

    /** The [TypeName] for [type], ignoring nullability — the caller decides how to add `| null`. */
    fun typeOf(type: KType): TypeName {
        val klass = type.classifier as? KClass<*> ?: return TypeName.implicit("unknown")
        options.scalars[klass.qualifiedName]?.let { return TypeName.implicit(it.reference) }
        if (isOpaqueJson(klass)) return TypeName.implicit("unknown")
        if (isMap(klass)) {
            val value = type.arguments.getOrNull(1)?.type ?: return TypeName.recordType(TypeName.STRING, TypeName.implicit("unknown"))
            val key = type.arguments.getOrNull(0)?.type
            return TypeName.recordType(if (key != null) typeOf(key) else TypeName.STRING, nullableTypeOf(value))
        }
        if (isCollection(klass)) {
            val element = type.arguments.firstOrNull()?.type ?: return TypeName.arrayShorthandType(TypeName.implicit("unknown"))
            return TypeName.arrayShorthandType(nullableTypeOf(element))
        }
        // A type that erases its own payload cannot be described, and the advice below would send
        // whoever hit it down two wrong paths — it is not a payload type to be declared, and it is
        // not a scalar to be spelled. Say what is actually wrong instead.
        // Something the generator cannot describe. What happens next is the project's call: refuse,
        // say so and emit `unknown`, or emit `unknown` quietly. `unknown` is the only honest
        // spelling in the last two cases — anything narrower would be a guess the caller would then
        // trust.
        untypedPayload(type)?.let { reason ->
            when (options.untypedPayloads) {
                UntypedPolicy.Fail -> error(reason)
                UntypedPolicy.Warn -> options.onWarning(reason)
                UntypedPolicy.Ignore -> Unit
            }
            return TypeName.implicit("unknown")
        }

        // A name nothing declares is a file that does not compile, and it used to be emitted
        // silently: a `java.util.Locale` on a payload became `locale: Locale` with no `Locale` in
        // the file. Refusing here is the same rule the emitter applies to a param shape it cannot
        // express — say what is missing, rather than produce something that looks finished.
        check(isDto(type)) {
            "${klass.qualifiedName} is on the client surface and the generator has no spelling for it: it is in none of " +
                "the dtoPackages (${options.dtoPackages.joinToString()}) and has no entry in scalars. Add its package to " +
                "dtoPackages if it is a payload type, or map it in scalars if your serializer writes it as a scalar — " +
                "`Scalar.named(\"${klass.simpleName}\", \"string\")`."
        }
        return TypeName.implicit(klass.simpleName ?: "unknown")
    }

    /** [typeOf] plus `| null` when the Kotlin type is nullable. */
    fun nullableTypeOf(type: KType): TypeName = if (type.isMarkedNullable) TypeName.unionType(typeOf(type), TypeName.NULL) else typeOf(type)

    /** The one-line spelling of [typeOf]. */
    fun render(type: KType): String = typeOf(type).toString()

    /** The one-line spelling of [nullableTypeOf]. */
    fun renderNullable(type: KType): String = nullableTypeOf(type).toString()

    /** Everything the emitter has to declare for `roots` to resolve. */
    data class Reachable(
        val dtos: List<KClass<*>>,
        /**
         * The scalars that have to be declared, in first-seen order and distinct by name — two
         * Kotlin types may legitimately map onto one alias.
         */
        val namedScalars: List<Scalar>,
    )

    /** Convenience for callers that only care about the payload types — the contract lint does. */
    fun reachableDtos(roots: List<KType>): List<KClass<*>> = reachable(roots).dtos

    /**
     * Every DTO and named scalar reachable from [roots], including the roots.
     * Depth-first so a type is declared before whatever refers to it.
     *
     * One traversal for both, on purpose: a second walk that collected scalars separately would be
     * a copy of this one, and the copy is what stops looking through the property one level down.
     */
    fun reachable(roots: List<KType>): Reachable {
        val ordered = LinkedHashSet<KClass<*>>()
        val scalars = LinkedHashMap<String, Scalar>()
        // Marked on entry, not on completion. A DTO that reaches itself — a message quoting a
        // message — would otherwise recurse until the stack runs out, because `ordered` is only
        // written after the walk returns.
        val visiting = mutableSetOf<KClass<*>>()

        fun visit(type: KType) {
            val klass = type.classifier as? KClass<*> ?: return
            if (isCollection(klass) || isMap(klass)) {
                for (argument in type.arguments.mapNotNull { it.type }) visit(argument)
                return
            }
            options.scalars[klass.qualifiedName]
                ?.takeIf { it.declaration != null }
                ?.let { scalars.putIfAbsent(it.reference, it) }
            if (!isDto(type) || !visiting.add(klass)) return
            // An enum still has to be declared, but its members are the JVM's business, not the
            // wire's — walking them would pull `name`/`ordinal` in as if they were fields.
            if (isEnum(klass)) {
                ordered.add(klass)
                return
            }
            if (isValueClass(klass)) {
                // The type it unwraps to may itself be a named scalar: `value class Timestamp(val at: Instant)`.
                visit(valueClassUnderlyingType(klass))
                ordered.add(klass)
                return
            }
            // A sealed base contributes nothing of its own: the union is its variants, so those
            // are what have to be declared — and declared first, since the alias names them.
            if (isSealedUnion(klass)) {
                for ((variant, _) in unionVariants(klass)) visit(variant.starProjectedType)
                ordered.add(klass)
                return
            }
            for (property in klass.memberProperties) visit(property.returnType)
            ordered.add(klass)
        }
        for (root in roots) visit(root)
        return Reachable(ordered.toList(), scalars.values.toList())
    }

    /**
     * What the property is called **on the wire**, which is not always what Kotlin calls it.
     *
     * `@JsonProperty("display_name")` renames a field, and emitting the Kotlin name instead
     * produces a client that compiles, reads `undefined` and fails nowhere — the exact shape of
     * failure this generator exists to prevent.
     *
     * Looked for in all four places the annotation can land, because where it ends up depends on
     * how the class is written rather than on what its author meant: Kotlin puts an untargeted
     * annotation on the constructor *parameter* when the annotation allows one, and on the backing
     * field or the getter when a use-site target says so.
     */
    fun wireName(
        declaring: KClass<*>,
        property: KProperty1<*, *>,
    ): String {
        val declared =
            jacksonAnnotations(declaring, property)
                .filterIsInstance<JsonProperty>()
                .firstOrNull()
                ?.value
        // `@JsonProperty` with no value is "keep the name" rather than "rename to nothing".
        return declared?.takeIf { it.isNotEmpty() } ?: property.name
    }

    /**
     * True when Jackson leaves the property out of the JSON entirely.
     *
     * Declaring it anyway is the same failure as the wrong name, one step further along: the client
     * gets a field that no payload ever carries and no call site can tell.
     */
    fun isIgnored(
        declaring: KClass<*>,
        property: KProperty1<*, *>,
    ): Boolean = jacksonAnnotations(declaring, property).any { it is JsonIgnore && it.value }

    /**
     * Refuses a naming strategy rather than emitting names it has not applied.
     *
     * `@JsonNaming(SnakeCaseStrategy::class)` renames every property at once, and it lives in
     * jackson-databind, which this library deliberately does not depend on — so the strategy cannot
     * be run, only detected. Emitting the Kotlin names in its presence would rename the whole
     * payload behind the client's back.
     */
    fun refuseNamingStrategy(declaring: KClass<*>) {
        val naming = declaring.java.annotations.firstOrNull { it.annotationClass.qualifiedName == JSON_NAMING }
        check(naming == null) {
            "${declaring.qualifiedName} carries @JsonNaming, which renames every property, and the generator cannot " +
                "run the strategy — it reads annotations and does not depend on jackson-databind. Rename the " +
                "properties with @JsonProperty, which it can read, or leave this type out of the client."
        }
    }

    /** The annotation wherever Kotlin put it: parameter, property, backing field or getter. */
    private fun jacksonAnnotations(
        declaring: KClass<*>,
        property: KProperty1<*, *>,
    ): List<Annotation> =
        declaring.primaryConstructor
            ?.parameters
            ?.firstOrNull { it.name == property.name }
            ?.annotations
            .orEmpty() + property.annotations +
            property.javaField
                ?.annotations
                ?.toList()
                .orEmpty() +
            property.getter.annotations

    /**
     * True when a **response** field may be absent from the JSON.
     *
     * Jackson writes a defaulted field regardless — the default decides what is written, not whether
     * it is written. Only `@JsonInclude(NON_NULL)` on the declaring class can drop a field, and then
     * only a null one. Reading "has default" as "optional" here would mark most of a response
     * surface optional and make every consumer null-check fields that are always present.
     */
    fun isOptionalResponseField(
        declaring: KClass<*>,
        propertyType: KType,
    ): Boolean {
        val include = declaring.java.getAnnotation(JsonInclude::class.java) ?: return false
        return include.value == JsonInclude.Include.NON_NULL && propertyType.isMarkedNullable
    }

    /** True when a **request** field may be omitted: the Kotlin constructor supplies a default. */
    fun isOptionalRequestField(
        declaring: KClass<*>,
        propertyName: String,
    ): Boolean =
        declaring.primaryConstructor
            ?.parameters
            ?.firstOrNull { it.name == propertyName }
            ?.isOptional == true
}
