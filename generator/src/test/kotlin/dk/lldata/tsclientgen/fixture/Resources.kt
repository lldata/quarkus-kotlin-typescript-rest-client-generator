package dk.lldata.tsclientgen.fixture

import dk.lldata.tsclientgen.TypeScriptApi
import jakarta.ws.rs.BeanParam
import jakarta.ws.rs.CookieParam
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.UriInfo
import org.jboss.resteasy.reactive.RestResponse

/**
 * A resource surface with one of everything the emitter handles, and nothing that needs a server.
 *
 * The bodies never run: the generator reads signatures and annotations, so `TODO()` is the honest
 * implementation here and keeps the fixture about the contract.
 */

class BookQuery(
    @QueryParam("q")
    val q: String? = null,
    @QueryParam("sort")
    @DefaultValue("TITLE")
    val sort: SortField = SortField.TITLE,
    @QueryParam("movedFrom")
    val movedFrom: Shelf? = null,
    @QueryParam("limit")
    @DefaultValue("20")
    val limitRaw: Int = 20,
)

@TypeScriptApi
@Path("/api/shelf/{shelf}/books")
class BooksResource {
    /** A `@BeanParam` holder, flattened to its query params, plus a node that is also a namespace. */
    @GET
    fun list(
        @PathParam("shelf") shelf: String,
        @BeanParam query: BookQuery,
    ): BookListResponse = TODO()

    @POST
    fun add(
        @PathParam("shelf") shelf: String,
        body: AddBookRequest,
    ): BookDetailResponse = TODO()

    @GET
    @Path("/{isbn}")
    fun detail(
        @PathParam("shelf") shelf: String,
        @PathParam("isbn") isbn: String,
        @QueryParam("lang") lang: String?,
    ): BookDetailResponse = TODO()

    /** `RestResponse<T>` is a wrapper; the payload is what reaches the wire. */
    @POST
    @Path("/{isbn}/borrow")
    fun borrow(
        @PathParam("shelf") shelf: String,
        @PathParam("isbn") isbn: String,
    ): RestResponse<BorrowResponse> = TODO()

    /** A reserved word as a segment: legal as a property, so the client keeps the wire's name. */
    @POST
    @Path("/{isbn}/return")
    fun returnCopy(
        @PathParam("shelf") shelf: String,
        @PathParam("isbn") isbn: String,
    ): BookDetailResponse = TODO()

    /** Bodyless, and everything ambient: a cookie and an injected context are not client arguments. */
    @POST
    @Path("/{isbn}/remove")
    fun remove(
        @PathParam("shelf") shelf: String,
        @PathParam("isbn") isbn: String,
        @CookieParam("session") session: String?,
        @Context uriInfo: UriInfo,
    ): RestResponse<Void> = TODO()
}

@TypeScriptApi
@Path("/api/renamed")
class RenamedResource {
    @GET
    fun renamed(): RenamedResponse = TODO()

    @POST
    fun rename(body: RenamedRequest): RenamedResponse = TODO()
}

/** Only used by the test that asserts a naming strategy is refused. */
@TypeScriptApi
@Path("/api/naming")
class NamingStrategyResource {
    @GET
    fun naming(): NamingStrategyResponse = TODO()
}

/** The plain-JAX-RS shape: a Response, which holds a body without saying what it is. */
@TypeScriptApi
@Path("/api/untyped")
class UntypedResponseResource {
    @GET
    fun untyped(): jakarta.ws.rs.core.Response = TODO()
}

@TypeScriptApi
@Path("/api/ignored")
class IgnoredResource {
    @GET
    fun ignored(): IgnoredUnmappableResponse = TODO()
}

@TypeScriptApi
@Path("/api/locale")
class LocaleResource {
    @GET
    fun unknownScalar(): UnknownScalarResponse = TODO()
}

@TypeScriptApi
@Path("/api/comments")
class CommentsResource {
    @GET
    fun tree(): CommentResponse = TODO()
}

/** No `@TypeScriptApi`: read into the model, left out of the client. */
@Path("/api/ops")
class OpsResource {
    @GET
    @Path("/stats")
    fun stats(): Map<String, Int> = TODO()
}

/** Only used by tests that assert a refusal — a header the caller would have to set itself. */
@TypeScriptApi
@Path("/api/keyed")
class ApiKeyedResource {
    @GET
    fun read(
        @HeaderParam("X-Api-Key") apiKey: String,
    ): BookListResponse = TODO()
}
