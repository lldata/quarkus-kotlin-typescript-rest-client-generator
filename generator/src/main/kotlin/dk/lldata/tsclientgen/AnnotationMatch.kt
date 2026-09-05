package dk.lldata.tsclientgen

/**
 * An annotation to look for, optionally with one member fixed to a value.
 *
 * ```kotlin
 * AnnotationMatch("com.acme.PublicApi")
 * AnnotationMatch("org.eclipse.microprofile.openapi.annotations.Operation", "hidden", true)
 * ```
 *
 * Named rather than referenced, because the annotation belongs to the consuming project and is
 * never on the generator's own classpath — which is the point. The generator interoperates with
 * whatever a project already marks things with, and depends on none of it.
 *
 * One member, not a set of them. The case that motivated this is `@Operation(hidden = true)`, and a
 * map of members with "all must match" is a query language arriving one convenience at a time. If a
 * real case ever needs two, that is a decision to take with the case in hand.
 *
 * @param name the annotation's fully qualified name
 * @param member the member to compare, or null to match the annotation whatever its members say
 * @param value what [member] must equal. An `Enum` compares equal to its `name`, so a constant can
 *   be written as a string without the generator depending on the enum
 */
data class AnnotationMatch(
    val name: String,
    val member: String? = null,
    val value: Any? = null,
) {
    init {
        require(name.isNotBlank()) { "an AnnotationMatch needs an annotation name." }
        // A Java annotation member can never be null, so a member named without a value cannot mean
        // "equals null" — it can only be half-written.
        require((member == null) == (value == null)) {
            "AnnotationMatch($name) sets ${if (member == null) "value" else "member"} without the other; " +
                "filter on a member and a value together, or on neither."
        }
    }

    /** True when [element] carries this annotation, and its [member] equals [value] if one is fixed. */
    internal fun matches(element: java.lang.reflect.AnnotatedElement): Boolean {
        val annotation = element.annotations.firstOrNull { it.annotationClass.java.name == name } ?: return false
        if (member == null) return true

        val method =
            runCatching { annotation.annotationClass.java.getMethod(member) }.getOrNull()
                ?: error(
                    "AnnotationMatch($name) filters on member '$member', which @$name does not declare. " +
                        "Its members are ${annotation.annotationClass.java.declaredMethods.map { it.name }.sorted()}.",
                )
        val actual = method.invoke(annotation)
        // An enum constant is spelled as its name here, so a project can match one without the
        // generator having the enum on its classpath.
        return if (actual is Enum<*> && value is String) actual.name == value else actual == value
    }
}
