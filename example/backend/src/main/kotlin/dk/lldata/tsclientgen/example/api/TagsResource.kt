package dk.lldata.tsclientgen.example.api

import dk.lldata.tsclientgen.example.domain.Catalogue
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

/**
 * On the client surface by name, not by `@TypeScriptApi` — see `clientResources` in
 * `ExampleClient.kt`. Standing in for a resource whose source you do not own, which has nothing
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
