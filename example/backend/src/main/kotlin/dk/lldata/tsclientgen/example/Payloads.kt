package dk.lldata.tsclientgen.example

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue
import dk.lldata.tsclientgen.TypeScriptValueObject
import java.time.Instant

// The wire vocabulary of the example: a shelf of books, borrowed and returned.
//
// Every shape here exists to show one thing the generator has a rule for. Compare each declaration
// with what it becomes in example/web/src/generated/client.ts.

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

/**
 * Shared vocabulary rather than one endpoint's answer: it appears in the list, in the detail and in
 * the return, so `*Response` would be the wrong name and the emitter would declare it three times.
 */
@TypeScriptValueObject
data class BookDto(
    val isbn: Isbn,
    val title: String,
    val author: String,
    val condition: Condition,
    val tags: List<String>,
    val addedAt: Instant,
    /** Nullable, and always written: a plain `null` is how the client learns the copy is on the shelf. */
    val borrowedBy: String?,
)

@TypeScriptValueObject
data class ShelfDto(
    val slug: ShelfSlug,
    val name: String,
    val bookCount: Int,
)

data class ShelvesResponse(
    val shelves: List<ShelfDto>,
)

data class BookListResponse(
    val books: List<BookDto>,
    /** Before `limit` was applied, so the page can say "12 of 30". */
    val total: Int,
    /** A `Map` becomes a `Record<string, number>`. */
    val tagCounts: Map<String, Int>,
)

/**
 * The only class here that omits nulls, which is the only thing that can make a response field
 * optional. Everywhere else a nullable field is `T | null` and always present.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class BookDetailResponse(
    val book: BookDto,
    /** Absent from the JSON when there is no note — `note?: string | null` in the client. */
    val note: String?,
    val alsoOnThisShelf: List<BookDto>,
)

/** A request reads the other way round: whatever Kotlin can default, the caller may omit. */
data class AddBookRequest(
    val isbn: Isbn,
    val title: String,
    val author: String,
    val condition: Condition = Condition.NEW,
    val tags: List<String> = emptyList(),
)

data class BorrowRequest(
    val borrower: String,
    val days: Int = 14,
)

/**
 * A sealed hierarchy is a discriminated union on the wire, and the client can narrow it:
 * `if (result.outcome === "refused") result.reason`.
 *
 * `EXISTING_PROPERTY` keeps the variants writing `outcome` themselves, so the JSON is unchanged by
 * the annotation; `@JsonSubTypes` is what tells the generator which literal each variant fixes it
 * to, because reflection cannot read a property initializer without an instance.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "outcome", visible = true)
@JsonSubTypes(
    JsonSubTypes.Type(value = BorrowAccepted::class, name = "accepted"),
    JsonSubTypes.Type(value = BorrowRefused::class, name = "refused"),
)
sealed interface BorrowResponse {
    val outcome: BorrowOutcome
}

data class BorrowAccepted(
    val borrower: String,
    val dueAt: Instant,
) : BorrowResponse {
    override val outcome: BorrowOutcome = BorrowOutcome.ACCEPTED
}

data class BorrowRefused(
    val reason: String,
    val borrowedBy: String,
) : BorrowResponse {
    override val outcome: BorrowOutcome = BorrowOutcome.REFUSED
}
