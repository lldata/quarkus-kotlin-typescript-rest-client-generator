package dk.lldata.tsclientgen.example

import dk.lldata.tsclientgen.TypeScriptApi
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

/** `api.shelves.get()` — the root of the tree, and the page's first call. */
@TypeScriptApi
@Path("/api/shelves")
@Produces(MediaType.APPLICATION_JSON)
class ShelvesResource(
    private val catalogue: Catalogue,
) {
    @GET
    fun shelves(): ShelvesResponse = ShelvesResponse(catalogue.shelves())
}
