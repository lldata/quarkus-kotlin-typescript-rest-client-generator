package dk.lldata.tsclientgen.example.api

import dk.lldata.tsclientgen.TypeScriptApi
import dk.lldata.tsclientgen.example.domain.AddBookRequest
import dk.lldata.tsclientgen.example.domain.BookDetailResponse
import dk.lldata.tsclientgen.example.domain.BookListResponse
import dk.lldata.tsclientgen.example.domain.BookSearch
import dk.lldata.tsclientgen.example.domain.BorrowRequest
import dk.lldata.tsclientgen.example.domain.BorrowResponse
import dk.lldata.tsclientgen.example.domain.Catalogue
import dk.lldata.tsclientgen.example.domain.Condition
import dk.lldata.tsclientgen.example.domain.SortField
import jakarta.ws.rs.BeanParam
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.CookieParam
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.UriInfo
import org.jboss.resteasy.reactive.RestResponse

/**
 * A `@BeanParam` holder: the query string, bound.
 *
 * On the wire these are four ordinary query parameters, which is what the client is told —
 * `limitRaw` never reaches it, because the wire calls the parameter `limit`. [toSearch] is the seam:
 * past it there is only a [BookSearch], and the catalogue never sees an HTTP concept.
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

    fun toSearch() = BookSearch(term = q, condition = condition, sort = sort, limit = limitRaw)
}

/**
 * `/api/shelves/{shelf}/books` is both an endpoint and a namespace — `GET` and `POST` live on it,
 * and `{isbn}` hangs below it. That is why the verb sits at the leaf of the emitted tree:
 * `api.shelves.shelf("attic").books.get()` and `…books.isbn("…").borrow.post(body)`.
 *
 * Each method binds the request and hands it to [Catalogue]. What is left here is the part that is
 * genuinely about HTTP: where each parameter comes from, and which answer is a status rather than a
 * body. Nothing decides anything about books.
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
    ): BookListResponse = catalogue.search(shelf, query.toSearch())

    @POST
    fun add(
        @PathParam("shelf") shelf: String,
        body: AddBookRequest,
    ): BookDetailResponse = catalogue.add(shelf, body)

    @GET
    @Path("/{isbn}")
    fun detail(
        @PathParam("shelf") shelf: String,
        @PathParam("isbn") isbn: String,
    ): BookDetailResponse = catalogue.detail(shelf, isbn)

    /** Both outcomes are a 200: the client narrows the union rather than interpreting a status. */
    @POST
    @Path("/{isbn}/borrow")
    fun borrow(
        @PathParam("shelf") shelf: String,
        @PathParam("isbn") isbn: String,
        body: BorrowRequest,
    ): BorrowResponse = catalogue.borrow(shelf, isbn, body)

    /**
     * A reserved word as a path segment. Legal as a TypeScript property, so the client keeps the
     * wire's spelling: `api.shelves.shelf("attic").books.isbn("…").return.post()`.
     */
    @POST
    @Path("/{isbn}/return")
    fun returnCopy(
        @PathParam("shelf") shelf: String,
        @PathParam("isbn") isbn: String,
    ): BookDetailResponse = catalogue.returnCopy(shelf, isbn)

    /**
     * `DELETE` on the book itself, rather than a `POST` to an action segment. Both idioms are here
     * on purpose — `returnCopy` above puts the action in the path — because the generator has no
     * opinion about which a project picks, and the example is where that has to be visible.
     *
     * Sharing a node with [detail] is the point: `books.isbn(x)` is both an endpoint and a
     * namespace, and the verb at the leaf is what lets `get` and `delete` coexist there.
     *
     * Removal has nothing to answer with, so the status is the whole answer. `RestResponse<Void>`
     * is emitted as `Promise<void>`, and the transport resolves on the status rather than parsing
     * an empty response. A `Unit` return would say the same thing; this spelling is explicit about
     * the 204.
     *
     * The cookie and the injected `UriInfo` are ambient — the browser sends one and the server
     * supplies the other — so neither is a client argument, and neither is passed on.
     */
    @DELETE
    @Path("/{isbn}")
    fun remove(
        @PathParam("shelf") shelf: String,
        @PathParam("isbn") isbn: String,
        @CookieParam("session") session: String?,
        @Context uriInfo: UriInfo,
    ): RestResponse<Void> {
        catalogue.remove(shelf, isbn)
        return RestResponse.noContent()
    }
}
