package dk.lldata.tsclientgen.example.domain

import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * The whole domain, in memory. Restarting the app resets it.
 *
 * Every rule about books lives here — what a search matches, what sorting means, whether a copy can
 * be lent — and none of them mention HTTP. The resources are a thin binding on top: they read the
 * request, call one method here, and let [CatalogueFailureMapper] turn a refusal into a status.
 *
 * There is no database on purpose: the example is about the contract between Kotlin and TypeScript,
 * and a persistence layer would be the largest thing in it without changing a line of the generated
 * client.
 */
@ApplicationScoped
class Catalogue {
    private val names = linkedMapOf("attic" to "The attic", "office" to "Office shelf")
    private val books = ConcurrentHashMap<String, MutableMap<String, Book>>()

    init {
        for (slug in names.keys) books[slug] = ConcurrentHashMap()
        put(
            "attic",
            book("978-0262510875", "Structure and Interpretation of Computer Programs", "Abelson", Condition.WORN, 900, "classic", "lisp"),
            book("978-0201616224", "The Pragmatic Programmer", "Hunt", Condition.USED, 430, "craft").copy(borrowedBy = "mia"),
        )
        put(
            "office",
            book("978-0134757599", "Refactoring", "Fowler", Condition.NEW, 40, "craft", "design"),
            book("978-1491950357", "Building Microservices", "Newman", Condition.USED, 210, "architecture"),
            book("978-0596007126", "Head First Design Patterns", "Freeman", Condition.USED, 620, "design", "classic"),
        )
    }

    fun shelves(): List<Shelf> = names.map { (slug, name) -> Shelf(ShelfSlug(slug), name, books.getValue(slug).size) }

    /** Filter, sort, then cut — and report the total from before the cut, which is what a page shows. */
    fun search(
        shelf: String,
        search: BookSearch,
    ): BookListResponse {
        val all = shelved(shelf)
        val matching =
            all
                .filter { search.term.isNullOrBlank() || it.matches(search.term) }
                .filter { search.condition == null || it.condition == search.condition }
        val sorted =
            when (search.sort) {
                SortField.TITLE -> matching.sortedBy { it.title }
                SortField.AUTHOR -> matching.sortedBy { it.author }
                SortField.ADDED -> matching.sortedByDescending { it.addedAt }
            }
        return BookListResponse(
            books = sorted.take(search.limit.coerceAtLeast(0)),
            total = matching.size,
            // Counted over the shelf, not over the matches: a facet the search itself narrowed to
            // one entry would be no use for widening it again.
            tagCounts = all.flatMap { it.tags }.groupingBy { it }.eachCount(),
        )
    }

    fun detail(
        shelf: String,
        isbn: String,
    ): BookDetailResponse = detailOf(shelf, require(shelf, isbn), note = null)

    fun add(
        shelf: String,
        request: AddBookRequest,
    ): BookDetailResponse {
        val shelved = books[shelf] ?: throw CatalogueFailure.UnknownShelf(shelf)
        if (shelved.containsKey(request.isbn.value)) throw CatalogueFailure.AlreadyOnShelf(request.isbn.value)
        val added =
            Book(
                isbn = request.isbn,
                title = request.title,
                author = request.author,
                condition = request.condition,
                tags = request.tags,
                addedAt = now(),
                borrowedBy = null,
            )
        shelved[added.isbn.value] = added
        return detailOf(shelf, added, note = "Just added.")
    }

    /** A copy already out is refused, and a refusal is an answer rather than a failure. */
    fun borrow(
        shelf: String,
        isbn: String,
        request: BorrowRequest,
    ): BorrowResponse {
        val book = require(shelf, isbn)
        val borrowedBy = book.borrowedBy
        if (borrowedBy != null) return BorrowRefused("Already borrowed", borrowedBy)
        store(shelf, book.copy(borrowedBy = request.borrower))
        return BorrowAccepted(request.borrower, now().plus(request.days.toLong(), ChronoUnit.DAYS))
    }

    fun returnCopy(
        shelf: String,
        isbn: String,
    ): BookDetailResponse {
        val returned = require(shelf, isbn).copy(borrowedBy = null)
        store(shelf, returned)
        return detailOf(shelf, returned, note = "Back on the shelf.")
    }

    fun remove(
        shelf: String,
        isbn: String,
    ) {
        require(shelf, isbn)
        books.getValue(shelf).remove(isbn)
    }

    private fun detailOf(
        shelf: String,
        book: Book,
        note: String?,
    ) = BookDetailResponse(
        book = book,
        note = note,
        alsoOnThisShelf = shelved(shelf).filter { it.isbn != book.isbn }.take(3),
    )

    private fun shelved(shelf: String): List<Book> = (books[shelf] ?: throw CatalogueFailure.UnknownShelf(shelf)).values.toList()

    private fun require(
        shelf: String,
        isbn: String,
    ): Book = (books[shelf] ?: throw CatalogueFailure.UnknownShelf(shelf))[isbn] ?: throw CatalogueFailure.UnknownBook(isbn)

    private fun store(
        shelf: String,
        book: Book,
    ) {
        books.getValue(shelf)[book.isbn.value] = book
    }

    private fun put(
        shelf: String,
        vararg seeded: Book,
    ) {
        for (book in seeded) store(shelf, book)
    }

    private fun book(
        isbn: String,
        title: String,
        author: String,
        condition: Condition,
        addedDaysAgo: Long,
        vararg tags: String,
    ) = Book(Isbn(isbn), title, author, condition, tags.toList(), now().minus(addedDaysAgo, ChronoUnit.DAYS), null)

    private fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
}
