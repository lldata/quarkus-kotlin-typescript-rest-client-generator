package dk.lldata.tsclientgen.model

import dk.lldata.tsclientgen.GeneratorOptions
import dk.lldata.tsclientgen.TypeScriptApi
import jakarta.ws.rs.BeanParam
import jakarta.ws.rs.CookieParam
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.FormParam
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.HttpMethod
import jakarta.ws.rs.MatrixParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Context
import java.io.File
import java.lang.reflect.Method
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.kotlinFunction

/**
 * Reads compiled JAX-RS resources into an [ApiModel].
 *
 * Compiled, not parsed: the annotations, the resolved generic types and Kotlin's nullability are
 * all present in the class file and none of them can be read reliably from source.
 */
class ApiModelReader(
    private val options: GeneratorOptions,
) {
    /**
     * Every resource under [classesDir], walked as a directory of `.class` files.
     *
     * Point this at the module's compiled main classes — `build/classes/kotlin/main`. Resolving it
     * from `codeSource.location` instead is a trap: that points at the classes directory or at the
     * jar depending on whether the `jar` task has run, and a jar walked as a directory yields no
     * classes at all.
     */
    fun read(classesDir: File): ApiModel {
        require(classesDir.isDirectory) { "compiled classes not found at $classesDir — has the module been compiled?" }
        val loader = javaClass.classLoader
        val classes =
            classesDir
                .walkTopDown()
                .filter { it.isFile && it.extension == "class" && !it.name.contains('$') }
                .mapNotNull { classFile ->
                    val binaryName =
                        classFile
                            .relativeTo(classesDir)
                            .path
                            .removeSuffix(".class")
                            .replace(File.separatorChar, '.')
                    runCatching { Class.forName(binaryName, false, loader) }.getOrNull()
                }.toList()
        val model = read(classes)
        // An empty read is always a broken read — a wrong directory, or a jar walked as one. Every
        // rule built on this model reports "no violations" for an empty model, so it has to fail
        // here rather than downstream, where it looks like a clean bill of health.
        check(model.endpoints.isNotEmpty()) { "no JAX-RS endpoints found under $classesDir — wrong directory?" }
        return model
    }

    /** The same read from classes you already have — what the tests use, and a REST-less entry point. */
    fun read(classes: List<Class<*>>): ApiModel =
        ApiModel(
            classes
                .filter { it.isAnnotationPresent(Path::class.java) }
                .flatMap { endpointsOf(it) }
                .sortedWith(compareBy({ it.path }, { it.verb })),
        )

    private fun endpointsOf(resource: Class<*>): List<Endpoint> {
        val basePath = resource.getAnnotation(Path::class.java).value
        val inTypeScriptApi = resource.isAnnotationPresent(TypeScriptApi::class.java)
        return resource.declaredMethods.mapNotNull { method ->
            val verb = verbOf(method) ?: return@mapNotNull null
            val kotlinFunction = runCatching { method.kotlinFunction }.getOrNull() ?: return@mapNotNull null
            val declared = kotlinFunction.returnType
            val unwrapped = unwrap(declared)
            Endpoint(
                resource = resource.simpleName,
                function = method.name,
                verb = verb,
                path = joinPaths(basePath, method.getAnnotation(Path::class.java)?.value),
                params = paramsOf(kotlinFunction),
                responseType = unwrapped?.second ?: declared,
                unwrappedFrom = unwrapped?.first,
                inTypeScriptApi = inTypeScriptApi,
            )
        }
    }

    /** A JAX-RS verb is any annotation that is itself meta-annotated `@HttpMethod`. */
    private fun verbOf(method: Method): String? =
        method.annotations
            .firstOrNull { it.annotationClass.java.isAnnotationPresent(HttpMethod::class.java) }
            ?.annotationClass
            ?.java
            ?.getAnnotation(HttpMethod::class.java)
            ?.value

    /**
     * The wrapper's name and the payload inside it, or null when the declared type *is* the payload.
     * Matched by qualified name so the generator needs neither RESTEasy nor Mutiny on its classpath.
     */
    private fun unwrap(type: KType): Pair<String, KType>? {
        val name = (type.classifier as? KClass<*>)?.qualifiedName ?: return null
        if (name !in options.wrapperTypes) return null
        val payload = type.arguments.singleOrNull()?.type ?: return null
        // `RestResponse<Uni<T>>` is not a shape anyone writes, but a wrapper of a wrapper costs one
        // line to handle and reads as a bug if it silently emits the inner wrapper's name.
        return name to (unwrap(payload)?.second ?: payload)
    }

    private fun paramsOf(function: KFunction<*>): List<Param> =
        function.parameters
            .filter { it.kind == KParameter.Kind.VALUE }
            .flatMap { parameter ->
                val (source, wireName) = sourceOf(parameter)
                if (source == ParamSource.BEAN) {
                    return@flatMap beanParams(parameter.type)
                }
                listOf(
                    Param(
                        name = parameter.name ?: "arg",
                        wireName = wireName,
                        source = source,
                        type = parameter.type,
                        nullable = parameter.type.isMarkedNullable,
                        defaultValue =
                            parameter.annotations
                                .filterIsInstance<DefaultValue>()
                                .firstOrNull()
                                ?.value,
                    ),
                )
            }

    /**
     * A `@BeanParam` is a container, not a parameter: on the wire its fields are ordinary query
     * (or path, or header) params. Flattening it here means the model describes what the HTTP call
     * actually looks like, so the emitter and the lint both see the real contract instead of an
     * opaque holder class.
     */
    private fun beanParams(beanType: KType): List<Param> {
        val beanClass = (beanType.classifier as? KClass<*>) ?: return emptyList()
        val properties = beanClass.memberProperties.associateBy { it.name }
        return beanClass.propertiesInDeclarationOrder().mapNotNull { name ->
            val property = properties[name] ?: return@mapNotNull null
            // Where a Kotlin annotation lands depends on how the holder is written: on the
            // parameter for a constructor property, on the backing field for a `var` in the class
            // body. Both are ordinary ways to write a `@BeanParam`, so look in every place rather
            // than making the generator care which one a project picked.
            val annotations = beanClass.annotationsFor(name) + property.annotations
            val (source, wireName) =
                annotations.firstNotNullOfOrNull { annotation ->
                    when (annotation) {
                        is QueryParam -> ParamSource.QUERY to annotation.value
                        is PathParam -> ParamSource.PATH to annotation.value
                        is HeaderParam -> ParamSource.HEADER to annotation.value
                        is CookieParam -> ParamSource.COOKIE to annotation.value
                        else -> null
                    }
                } ?: return@mapNotNull null
            Param(
                name = name,
                wireName = wireName,
                source = source,
                type = property.returnType,
                nullable = property.returnType.isMarkedNullable,
                defaultValue = annotations.filterIsInstance<DefaultValue>().firstOrNull()?.value,
            )
        }
    }

    /** Constructor properties first, then whatever the class body declares — the order a reader sees. */
    private fun KClass<*>.propertiesInDeclarationOrder(): List<String> =
        (primaryConstructor?.parameters?.mapNotNull { it.name }.orEmpty() + java.declaredFields.map { it.name }).distinct()

    private fun KClass<*>.annotationsFor(property: String): List<Annotation> =
        (
            primaryConstructor
                ?.parameters
                ?.firstOrNull { it.name == property }
                ?.annotations
                .orEmpty()
        ) +
            (
                java.declaredFields
                    .firstOrNull { it.name == property }
                    ?.annotations
                    ?.toList()
                    .orEmpty()
            )

    private fun sourceOf(parameter: KParameter): Pair<ParamSource, String?> {
        for (annotation in parameter.annotations) {
            when (annotation) {
                is PathParam -> return ParamSource.PATH to annotation.value
                is QueryParam -> return ParamSource.QUERY to annotation.value
                is HeaderParam -> return ParamSource.HEADER to annotation.value
                is CookieParam -> return ParamSource.COOKIE to annotation.value
                is FormParam -> return ParamSource.FORM to annotation.value
                is MatrixParam -> return ParamSource.MATRIX to annotation.value
                is BeanParam -> return ParamSource.BEAN to null
                is Context -> return ParamSource.CONTEXT to null
                else -> Unit
            }
        }
        // JAX-RS: the one parameter with no source annotation is the request body.
        return ParamSource.BODY to null
    }

    private fun joinPaths(
        base: String,
        sub: String?,
    ): String {
        val head = "/" + base.trim('/')
        val tail = sub?.trim('/').orEmpty()
        return if (tail.isEmpty()) head else "$head/$tail"
    }

    companion object {
        /**
         * The two ways a resource says "no body": a `Void` payload and a `Unit` return. Neither has
         * a TypeScript spelling — `Unit` emitted literally is an unresolved name — so both become
         * `Promise<void>`. Keeping the check here means emitters never repeat it.
         */
        fun isBodyless(type: KType): Boolean = type.classifier == Void::class || type.classifier == Unit::class
    }
}
