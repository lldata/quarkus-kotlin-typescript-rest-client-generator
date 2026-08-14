package dk.lldata.tsclientgen.fixture

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import dk.lldata.tsclientgen.TypeScriptValueObject
import java.time.Instant

/**
 * The payload shapes the generator has rules for, one of each.
 *
 * Public and top-level rather than private to a test, because the reader works off compiled classes
 * and several tests need the same shapes.
 */

@JvmInline
value class Isbn(
    val value: String,
)

@JvmInline
value class Copies(
    val value: Int,
)

enum class Condition(
    @JsonValue val wireValue: String,
) {
    NEW("new"),
    USED("used"),
}

enum class SortField {
    TITLE,
    AUTHOR,
}

/**
 * A wire spelling that is not the constant name, used as a query parameter — so it needs the
 * conversion hook JAX-RS uses, which `@JsonValue` is not.
 */
enum class Shelf(
    @JsonValue val wireValue: String,
) {
    ATTIC("attic"),
    OFFICE("office"),
    ;

    companion object {
        @JvmStatic
        fun fromString(value: String): Shelf = entries.first { it.wireValue == value }
    }
}

enum class BorrowOutcome(
    private val wireValue: String,
) {
    ACCEPTED("accepted"),
    REFUSED("refused"),
    ;

    @JsonValue
    fun jsonValue(): String = wireValue
}

/** Shared vocabulary rather than one endpoint's answer, so it keeps its own name. */
@TypeScriptValueObject
data class BookDto(
    val isbn: Isbn,
    val title: String,
    val condition: Condition,
    val copies: Copies,
    val tags: List<String>,
    val addedAt: Instant,
    val borrowedBy: String?,
)

data class BookListResponse(
    val books: List<BookDto>,
    val total: Int,
    val tagCounts: Map<String, Int>,
)

/** Only a `@JsonInclude(NON_NULL)` class can leave a field out, and then only a null one. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class BookDetailResponse(
    val book: BookDto,
    val note: String?,
    val alwaysThere: Int,
)

/** A request: the caller may omit whatever Kotlin can default. */
data class AddBookRequest(
    val isbn: Isbn,
    val title: String,
    val condition: Condition = Condition.NEW,
    val tags: List<String> = emptyList(),
)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "outcome", visible = true)
@JsonSubTypes(
    JsonSubTypes.Type(value = BorrowAccepted::class, name = "accepted"),
    JsonSubTypes.Type(value = BorrowRefused::class, name = "refused"),
)
sealed interface BorrowResponse {
    val outcome: BorrowOutcome
}

data class BorrowAccepted(
    val dueAt: Instant,
) : BorrowResponse {
    override val outcome: BorrowOutcome = BorrowOutcome.ACCEPTED
}

data class BorrowRefused(
    val reason: String,
) : BorrowResponse {
    override val outcome: BorrowOutcome = BorrowOutcome.REFUSED
}

/** A payload that reaches itself. It used to recurse until the stack ran out. */
data class CommentResponse(
    val text: String,
    val replies: List<CommentResponse>,
)

/**
 * The three ways the wire name differs from the Kotlin name — a rename on the constructor
 * parameter, a rename with a use-site target, and a property that is not on the wire at all.
 */
data class RenamedResponse(
    @JsonProperty("display_name") val displayName: String,
    @field:JsonProperty("shelf_count") val shelfCount: Int,
    @JsonIgnore val internalOnly: String,
    val plain: String,
)

/** A rename on a request body reads the same way, and the optionality still keys off Kotlin. */
data class RenamedRequest(
    @JsonProperty("display_name") val displayName: String,
    @JsonProperty("shelf_count") val shelfCount: Int = 1,
)

/**
 * A naming strategy renames every property at once, and it cannot be read — only detected. Emitting
 * the Kotlin names in its presence would rename the whole payload behind the client's back.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class NamingStrategyResponse(
    val displayName: String,
)

/** A property whose type the generator has no rule for: not a payload, not a known scalar. */
data class UnknownScalarResponse(
    val locale: java.util.Locale,
)
