package dk.lldata.tsclientgen.example

import dk.lldata.tsclientgen.TypeScriptApi
import jakarta.ws.rs.BeanParam
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.CookieParam
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import org.jboss.resteasy.reactive.RestResponse
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * A `@BeanParam` holder. On the wire these are three ordinary query parameters, and that is what
 * the client is told — `limitRaw` never reaches it, because the wire calls the parameter `limit`.
 */
class BookQuery {
    @QueryParam("q")
    var q: String? = null

    @QueryParam("condition")
    var condition: Condition? = null

    @QueryParam("sort")
    @DefaultValue("TITLE")
    var sort: SortField = SortField.TITLE

    @QueryParam("limit")
    @DefaultValue("50")
    var limitRaw: Int = 50
}

/**
 * `/api/shelves/{shelf}/books` is both an endpoint and a namespace — `GET` and `POST` live on it, and
 * `{isbn}` hangs below it. That is why the verb sits at the leaf of the emitted tree:
 * `api.shelves.shelf("attic").books.get()` and `…books.isbn("…").borrow.post(body)`.
 */
@TypeScriptApi
@Path("/api/shelves/{shelf}/books")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class BooksResource(
    private val catalogue: Catalogue,
) {
    @GET
    fun list(
        @PathParam("shelf") shelf: String,
        @BeanParam query: BookQuery,
    ): BookListResponse {
        val all = catalogue.booksOn(shelf.requireShelf())
        val matching =
            all
                .filter { book -> query.q.isNullOrBlank() || book.matches(query.q!!) }
                .filter { book -> query.condition == null || book.condition == query.condition }
        val sorted =
            when (query.sort) {
                SortField.TITLE -> matching.sortedBy { it.title }
                SortField.AUTHOR -> matching.sortedBy { it.author }
                SortField.ADDED -> matching.sortedByDescending { it.addedAt }
            }
        return BookListResponse(
            books = sorted.take(query.limitRaw.coerceAtLeast(0)),
            total = matching.size,
            tagCounts = all.flatMap { it.tags }.groupingBy { it }.eachCount(),
        )
    }

    @POST
    fun add(
        @PathParam("shelf") shelf: String,
        body: AddBookRequest,
    ): BookDetailResponse {
        val slug = shelf.requireShelf()
        if (catalogue.book(slug, body.isbn.value) != null) {
            throw failure(Response.Status.CONFLICT, "${body.isbn.value} is already on this shelf")
        }
        val added =
            catalogue.add(
                slug,
                BookDto(
                    isbn = body.isbn,
                    title = body.title,
                    author = body.author,
                    condition = body.condition,
                    tags = body.tags,
                    addedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS),
                    borrowedBy = null,
                ),
            )
        return detailOf(slug, added, note = "Just added.")
    }

    @GET
    @Path("/{isbn}")
    fun detail(
        @PathParam("shelf") shelf: String,
        @PathParam("isbn") isbn: String,
    ): BookDetailResponse {
        val slug = shelf.requireShelf()
        return detailOf(slug, requireBook(slug, isbn), note = null)
    }

    /**
     * The sealed response. Both outcomes are a 200 with a body the client narrows on `outcome`,
     * rather than a status code it has to interpret.
     */
    @POST
    @Path("/{isbn}/borrow")
    fun borrow(
        @PathParam("shelf") shelf: String,
        @PathParam("isbn") isbn: String,
        body: BorrowRequest,
    ): RestResponse<BorrowResponse> {
        val slug = shelf.requireShelf()
        val book = requireBook(slug, isbn)
        val borrowedBy = book.borrowedBy
        if (borrowedBy != null) {
            return RestResponse.ok(BorrowRefused("Already borrowed", borrowedBy))
        }
        catalogue.replace(slug, book.copy(borrowedBy = body.borrower))
        val dueAt = Instant.now().plus(body.days.toLong(), ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        return RestResponse.ok(BorrowAccepted(body.borrower, dueAt))
    }

    /**
     * A reserved word as a path segment. Legal as a TypeScript property, so the client keeps the
     * wire's spelling: `api.shelves.shelf("attic").books.isbn("…").return.post()`.
     */
    @POST
    @Path("/{isbn}/return")
    fun returnCopy(
        @PathParam("shelf") shelf: String,
        @PathParam("isbn") isbn: String,
    ): BookDetailResponse {
        val slug = shelf.requireShelf()
        val returned = catalogue.replace(slug, requireBook(slug, isbn).copy(borrowedBy = null))
        return detailOf(slug, returned, note = "Back on the shelf.")
    }

    /**
     * No body at all: `RestResponse<Void>` is emitted as `Promise<void>`, and the transport resolves
     * on the status rather than trying to parse an empty response.
     *
     * The cookie and the injected `UriInfo` are ambient — the browser sends one and the server
     * supplies the other, so neither is a client argument.
     */
    @POST
    @Path("/{isbn}/remove")
    fun remove(
        @PathParam("shelf") shelf: String,
        @PathParam("isbn") isbn: String,
        @CookieParam("session") session: String?,
        @Context uriInfo: UriInfo,
    ): RestResponse<Void> {
        val slug = shelf.requireShelf()
        if (!catalogue.remove(slug, isbn)) throw failure(Response.Status.NOT_FOUND, "No such book: $isbn")
        return RestResponse.noContent()
    }

    private fun detailOf(
        slug: String,
        book: BookDto,
        note: String?,
    ) = BookDetailResponse(
        book = book,
        note = note,
        alsoOnThisShelf = catalogue.booksOn(slug).filter { it.isbn != book.isbn }.take(3),
    )

    private fun requireBook(
        slug: String,
        isbn: String,
    ): BookDto = catalogue.book(slug, isbn) ?: throw failure(Response.Status.NOT_FOUND, "No such book: $isbn")

    private fun String.requireShelf(): String =
        also {
            if (!catalogue.hasShelf(it)) throw failure(Response.Status.NOT_FOUND, "No such shelf: $it")
        }

    /**
     * An error body is outside the generated contract: the client types describe what a *successful*
     * call returns, and `ApiError.body` is `unknown` for exactly this reason. Sending JSON rather
     * than an empty status still costs nothing and gives the caller something to show.
     */
    private fun failure(
        status: Response.Status,
        message: String,
    ) = WebApplicationException(Response.status(status).entity(mapOf("message" to message)).build())

    private fun BookDto.matches(term: String): Boolean =
        title.contains(term, ignoreCase = true) ||
            author.contains(term, ignoreCase = true) ||
            tags.any { it.contains(term, ignoreCase = true) }
}
