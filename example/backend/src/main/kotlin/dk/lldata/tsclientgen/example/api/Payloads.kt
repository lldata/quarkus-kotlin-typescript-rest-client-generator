package dk.lldata.tsclientgen.example.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import dk.lldata.tsclientgen.example.domain.Book
import dk.lldata.tsclientgen.example.domain.BorrowOutcome
import dk.lldata.tsclientgen.example.domain.Condition
import dk.lldata.tsclientgen.example.domain.Isbn
import java.time.Instant

// The shapes that exist only because a call has to answer with something more than a `Book`. Where
// a plain domain type says it, there is no payload type here: `GET /api/shelves` answers with
// `List<Shelf>`, not a `ShelvesResponse` wrapping one field.

data class BookListResponse(
    val books: List<Book>,
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
    val book: Book,
    /** Absent from the JSON when there is no note — `note?: string | null` in the client. */
    val note: String?,
    val alsoOnThisShelf: List<Book>,
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
 *
 * Both arms are a 200. Refusing to lend a book is an answer, not an error, and the client narrows
 * on a field rather than interpreting a status code.
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
