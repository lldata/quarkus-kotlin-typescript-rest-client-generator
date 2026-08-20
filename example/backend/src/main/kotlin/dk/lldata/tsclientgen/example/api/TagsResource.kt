package dk.lldata.tsclientgen.example.api

import dk.lldata.tsclientgen.example.domain.Catalogue
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

/**
 * On the client surface because it carries `@Path`, like every other resource here.
 * to put the annotation on.
 */
@Path("/api/tags")
@Produces(MediaType.APPLICATION_JSON)
class TagsResource(
    private val catalogue: Catalogue,
) {
    @GET
    fun list(): List<String> = catalogue.tags()
}
