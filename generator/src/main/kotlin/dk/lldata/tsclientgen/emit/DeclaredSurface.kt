package dk.lldata.tsclientgen.emit

import dk.lldata.tsclientgen.model.Endpoint
import dk.lldata.typescriptpoet.TypeName
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * What the client declares for a set of endpoints, worked out once.
 *
 * [TypeScriptClientEmitter] writes it and [ContractDigest] hashes it, and they have to agree: a
 * second copy of this reasoning is how a stamp ends up describing a file the emitter no longer
 * writes, which is worse than no stamp at all.
 */
internal class DeclaredSurface(
    private val types: TypeScriptTypes,
    endpoints: List<Endpoint>,
) {
    val reachable: TypeScriptTypes.Reachable = types.reachable(TypeScriptClientEmitter.rootTypesOf(endpoints))

    val dtos: List<KClass<*>> get() = reachable.dtos

    /** Reachable from a request body, and so subject to the request optionality rule rather than the response one. */
    private val requestDtos: Set<KClass<*>> = types.reachableDtos(endpoints.mapNotNull { it.body?.type }).toSet()

    /** Each variant of a sealed union, to the discriminator property and the literal it fixes. */
    val discriminators: Map<KClass<*>, Pair<String, String>> =
        buildMap {
            for (base in reachable.dtos.filter { types.isSealedUnion(it) }) {
                val property = types.unionDiscriminatorProperty(base)
                for ((variant, literal) in types.unionVariants(base)) put(variant, property to literal)
            }
        }

    /** The members [dto] is declared with, in the order they are emitted. */
    fun membersOf(dto: KClass<*>): List<DeclaredMember> {
        types.refuseNamingStrategy(dto)
        val discriminator = discriminators[dto]
        val isRequest = dto in requestDtos
        // Declaration order, not alphabetical: the Kotlin constructor is the readable order.
        val order =
            dto.primaryConstructor
                ?.parameters
                ?.mapNotNull { it.name }
                .orEmpty()
        return dto.memberProperties
            .sortedBy { order.indexOf(it.name).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }
            .filterNot { types.isIgnored(dto, it) }
            .map { member ->
                // What the JSON calls it, which `@JsonProperty` can make different from what Kotlin
                // calls it. The Kotlin name is still the right key for asking Kotlin questions —
                // `isOptionalRequestField` looks the constructor parameter up by it.
                val name = types.wireName(dto, member)
                DeclaredMember(
                    name = name,
                    type =
                        if (name == discriminator?.first) {
                            // The variant fixes this to one literal, which is what makes the union
                            // narrowable: `if (result.outcome === "refused")` tells TypeScript which arm.
                            TypeName.literal(discriminator.second)
                        } else {
                            types.nullableTypeOf(member.returnType)
                        },
                    optional =
                        if (isRequest) {
                            types.isOptionalRequestField(dto, member.name)
                        } else {
                            types.isOptionalResponseField(dto, member.returnType)
                        },
                )
            }
    }
}

/** One property of an emitted interface, as it is declared. */
internal data class DeclaredMember(
    val name: String,
    val type: TypeName,
    val optional: Boolean,
)
