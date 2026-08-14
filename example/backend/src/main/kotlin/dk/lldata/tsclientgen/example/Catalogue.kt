package dk.lldata.tsclientgen.example

import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * The whole domain, in memory. Restarting the app resets it.
 *
 * There is no database on purpose: the example is about the contract between Kotlin and TypeScript,
 * and a persistence layer would be the largest thing in it without changing a line of the generated
 * client.
 */
@ApplicationScoped
class Catalogue {
    private val shelves = linkedMapOf<String, String>()
    private val books = ConcurrentHashMap<String, MutableMap<String, BookDto>>()

    init {
        addShelf("attic", "The attic")
        addShelf("office", "Office shelf")
        seed(
            "attic",
            BookDto(
                Isbn("978-0262510875"), "Structure and Interpretation of Computer Programs", "Abelson", Condition.WORN,
                listOf("classic", "lisp"), daysAgo(900), null,
            ),
            BookDto(
                Isbn("978-0201616224"), "The Pragmatic Programmer", "Hunt", Condition.USED,
                listOf("craft"), daysAgo(430), "mia",
            ),
        )
        seed(
            "office",
            BookDto(
                Isbn("978-0134757599"), "Refactoring", "Fowler", Condition.NEW,
                listOf("craft", "design"), daysAgo(40), null,
            ),
            BookDto(
                Isbn("978-1491950357"), "Building Microservices", "Newman", Condition.USED,
                listOf("architecture"), daysAgo(210), null,
            ),
            BookDto(
                Isbn("978-0596007126"), "Head First Design Patterns", "Freeman", Condition.USED,
                listOf("design", "classic"), daysAgo(620), null,
            ),
        )
    }

    fun shelves(): List<ShelfDto> = shelves.map { (slug, name) -> ShelfDto(ShelfSlug(slug), name, books[slug]?.size ?: 0) }

    fun hasShelf(slug: String): Boolean = shelves.containsKey(slug)

    fun booksOn(slug: String): List<BookDto> = books[slug]?.values?.toList().orEmpty()

    fun book(
        slug: String,
        isbn: String,
    ): BookDto? = books[slug]?.get(isbn)

    fun add(
        slug: String,
        book: BookDto,
    ): BookDto {
        books.getValue(slug)[book.isbn.value] = book
        return book
    }

    fun replace(
        slug: String,
        book: BookDto,
    ): BookDto = add(slug, book)

    fun remove(
        slug: String,
        isbn: String,
    ): Boolean = books[slug]?.remove(isbn) != null

    private fun addShelf(
        slug: String,
        name: String,
    ) {
        shelves[slug] = name
        books[slug] = ConcurrentHashMap()
    }

    private fun seed(
        slug: String,
        vararg seeded: BookDto,
    ) {
        for (book in seeded) add(slug, book)
    }

    private fun daysAgo(days: Long): Instant = Instant.now().minus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
}
