package dk.lldata.tsclientgen.example

import com.fasterxml.jackson.annotation.JsonValue
import dk.lldata.tsclientgen.TypeScriptValueObject
import java.time.Instant

// The domain, which is also the wire vocabulary. There is no second set of types that exist only to
// be serialized: a `Book` is a book, and `@TypeScriptValueObject` is how it says it keeps its own
// name in the client rather than being one endpoint's `*Response`.

/** A `@JvmInline value class` is a scalar on the wire — `export type Isbn = string`. */
@JvmInline
value class Isbn(
    val value: String,
)

@JvmInline
value class ShelfSlug(
    val value: String,
)

/** `@JsonValue` on a property: the wire carries `"new"`, never `NEW`. */
enum class Condition(
    @JsonValue val wireValue: String,
) {
    NEW("new"),
    USED("used"),
    WORN("worn"),
    ;

    companion object {
        /**
         * `@JsonValue` governs the JSON body, and nothing else. A query parameter is converted by
         * JAX-RS, which uses `valueOf` — so `?condition=worn` was a 404 while `?condition=WORN`
         * worked, and the generated client only ever sends the first, because that is what Jackson
         * writes everywhere else.
         *
         * `fromString` is the hook JAX-RS prefers for an enum, and it is what makes the wire
         * spelling mean one thing in both directions. `ENUM_QUERY_PARAM_CONVERTIBLE` in the
         * contract lint is what stops the next enum from forgetting.
         */
        @JvmStatic
        fun fromString(value: String): Condition =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("unknown condition: $value")
    }
}

/** No `@JsonValue`: Jackson writes the constant name, and so does the client. */
enum class SortField {
    TITLE,
    AUTHOR,
    ADDED,
}

/** `@JsonValue` on a method — the other way to write one, and the generator reads both. */
enum class BorrowOutcome(
    private val wireValue: String,
) {
    ACCEPTED("accepted"),
    REFUSED("refused"),
    ;

    @JsonValue
    fun jsonValue(): String = wireValue
}

@TypeScriptValueObject
data class Shelf(
    val slug: ShelfSlug,
    val name: String,
    val bookCount: Int,
)

@TypeScriptValueObject
data class Book(
    val isbn: Isbn,
    val title: String,
    val author: String,
    val condition: Condition,
    val tags: List<String>,
    val addedAt: Instant,
    /** Nullable, and always written: a plain `null` is how the client learns the copy is on the shelf. */
    val borrowedBy: String?,
) {
    fun matches(term: String): Boolean =
        title.contains(term, ignoreCase = true) ||
            author.contains(term, ignoreCase = true) ||
            tags.any { it.contains(term, ignoreCase = true) }
}

/**
 * What a caller is looking for, with the HTTP left out.
 *
 * The resource binds `?q=&condition=&sort=&limit=` into this; the catalogue never sees a query
 * string, and this type never reaches the client because no endpoint signature mentions it.
 */
data class BookSearch(
    val term: String?,
    val condition: Condition?,
    val sort: SortField,
    val limit: Int,
)

/**
 * What the catalogue says when it cannot do what was asked.
 *
 * Deliberately not a `WebApplicationException`: which status each of these is worth is the HTTP
 * layer's opinion, and [CatalogueFailureMapper] is where that opinion lives.
 */
sealed class CatalogueFailure(
    message: String,
) : RuntimeException(message) {
    class UnknownShelf(
        slug: String,
    ) : CatalogueFailure("No such shelf: $slug")

    class UnknownBook(
        isbn: String,
    ) : CatalogueFailure("No such book: $isbn")

    class AlreadyOnShelf(
        isbn: String,
    ) : CatalogueFailure("$isbn is already on this shelf")
}
