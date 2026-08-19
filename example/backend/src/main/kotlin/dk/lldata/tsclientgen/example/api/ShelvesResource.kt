package dk.lldata.tsclientgen.example.api

import dk.lldata.tsclientgen.TypeScriptApi
import dk.lldata.tsclientgen.example.domain.Catalogue
import dk.lldata.tsclientgen.example.domain.Shelf
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

/**
 * `api.shelves.get()` — the root of the tree, and the page's first call.
 *
 * It answers with `List<Shelf>` rather than a `ShelvesResponse` wrapping one field. A wrapper earns
 * its place when there is something to say alongside the list, as `BookListResponse` has in `total`
 * and `tagCounts`; here there is nothing, and the client reads `Shelf[]`.
 */
@TypeScriptApi
@Path("/api/shelves")
@Produces(MediaType.APPLICATION_JSON)
class ShelvesResource(
    private val catalogue: Catalogue,
) {
    @GET
    fun shelves(): List<Shelf> = catalogue.shelves()
}
