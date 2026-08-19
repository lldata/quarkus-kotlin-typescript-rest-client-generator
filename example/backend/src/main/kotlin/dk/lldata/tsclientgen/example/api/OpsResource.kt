package dk.lldata.tsclientgen.example.api

import dk.lldata.tsclientgen.example.domain.Catalogue
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

/**
 * No `@TypeScriptApi`, so no client is generated for it — an operator reads this with `curl`, and
 * nothing in the browser should call it.
 *
 * It is still read into the model, which is how a rule about the HTTP API itself (the verb rule)
 * can see endpoints the client never touches.
 */
@Path("/api/ops")
@Produces(MediaType.APPLICATION_JSON)
class OpsResource(
    private val catalogue: Catalogue,
) {
    @GET
    @Path("/stats")
    fun stats(): Map<String, Int> = catalogue.shelves().associate { it.slug.value to it.bookCount }
}
