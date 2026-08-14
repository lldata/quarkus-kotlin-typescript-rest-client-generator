package dk.lldata.tsclientgen.emit

import dk.lldata.tsclientgen.model.Endpoint
import dk.lldata.tsclientgen.model.Param

/**
 * The endpoint paths as a tree, which is what the client mirrors.
 *
 * A literal segment becomes a property and a dynamic one a method, so a call reads like the URL it
 * sends: `api.shelf("attic").books.isbn("978-…").borrow.post(body)`.
 *
 * The verb sits at the leaf because a node is often both an endpoint and a namespace —
 * `POST /shelf/{s}/books` is create and `books/{isbn}` is a child of the same node. Putting the verb
 * anywhere else needs a rule for the clash; putting it at the leaf needs none.
 */
class ApiNode(
    /** Segment names from the root, used to name this node's factory function. */
    val trail: List<String>,
) {
    val literals = linkedMapOf<String, ApiNode>()

    /** At most one, because a position in a path is either a literal or a variable, never both. */
    var dynamic: DynamicChild? = null
        private set

    /** Verb to endpoint. Distinct by construction: no two endpoints share a verb and a path. */
    val operations = linkedMapOf<String, Endpoint>()

    data class DynamicChild(
        val param: Param,
        val node: ApiNode,
    )

    fun literalChild(segment: String): ApiNode = literals.getOrPut(segment) { ApiNode(trail + segment) }

    /**
     * Refuses two names for the same position rather than picking one: the name becomes the
     * client's method, so `messages.threadId(4)` and `messages.id(4)` would be the same call
     * spelled two ways, and only one of them would exist.
     */
    fun dynamicChild(param: Param): ApiNode {
        val existing = dynamic
        if (existing != null) {
            check(existing.param.wireName == param.wireName) {
                "/${trail.joinToString("/")} has a variable segment named both " +
                    "'${existing.param.wireName}' and '${param.wireName}'. One position needs one name."
            }
            return existing.node
        }
        val child = ApiNode(trail + (param.wireName ?: param.name))
        dynamic = DynamicChild(param, child)
        return child
    }

    /** `shelfShelfBooks` — unique because the path it came from is. */
    fun factoryName(): String =
        if (trail.isEmpty()) {
            ROOT_FACTORY
        } else {
            trail
                .mapIndexed { index, segment ->
                    val camel = camelCase(segment)
                    if (index == 0) camel else camel.replaceFirstChar { it.uppercase() }
                }.joinToString("") + "Node"
        }

    /** Depth-first, children before parents, so a factory is declared before whoever calls it. */
    fun descendants(): List<ApiNode> =
        buildList {
            for (child in literals.values) addAll(child.descendants())
            dynamic?.let { addAll(it.node.descendants()) }
            add(this@ApiNode)
        }

    companion object {
        /** The exported entry point: `createApi(config)` returns the root node's members. */
        const val ROOT_FACTORY = "createApi"

        /**
         * The paths as a tree, with [rootPath] stripped — every endpoint shares it, so the root
         * *is* that path and no node has to carry it.
         */
        fun of(
            endpoints: List<Endpoint>,
            rootPath: String,
        ): ApiNode {
            val root = ApiNode(emptyList())
            for (endpoint in endpoints.sortedBy { it.path }) {
                var node = root
                check(endpoint.path.startsWith(rootPath)) {
                    "${endpoint.verb} ${endpoint.path} is on the client surface but is not under the configured " +
                        "root path '$rootPath'. Widen rootPath, or leave that resource out of the client."
                }
                for (segment in endpoint.path
                    .removePrefix(rootPath)
                    .split("/")
                    .filter { it.isNotEmpty() }) {
                    node =
                        if (segment.startsWith("{") && segment.endsWith("}")) {
                            // `{id: [0-9]+}` — the regex is the server's business, the name is the client's.
                            val wire = segment.trim('{', '}').substringBefore(':').trim()
                            val param =
                                endpoint.pathParams.firstOrNull { it.wireName == wire }
                                    ?: error("${endpoint.path} has no path parameter for '$wire'.")
                            node.dynamicChild(param)
                        } else {
                            node.literalChild(segment)
                        }
                }
                check(node.operations.put(endpoint.verb, endpoint) == null) {
                    "${endpoint.verb} ${endpoint.path} is declared twice; the client could only expose one."
                }
            }
            return root
        }

        /** `for-sale` becomes `forSale`; the wire keeps the hyphen, the client cannot. */
        fun camelCase(segment: String): String =
            segment
                .split("-", "_", ".")
                .filter { it.isNotEmpty() }
                .mapIndexed { index, part ->
                    if (index == 0) part else part.replaceFirstChar { it.uppercase() }
                }.joinToString("")
    }
}
