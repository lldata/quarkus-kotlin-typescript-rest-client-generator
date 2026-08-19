package dk.lldata.tsclientgen.example.api

import dk.lldata.tsclientgen.example.domain.CatalogueFailure
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

/**
 * The HTTP layer's opinion about what a [CatalogueFailure] is worth.
 *
 * Here rather than in the resources so that no method needs a `try`/`catch` and no two of them can
 * disagree about which status an unknown shelf is — and here rather than in the catalogue, which
 * has no business knowing that 404 exists.
 *
 * An error body is outside the generated contract: the client types describe what a *successful*
 * call returns, and `ApiError.body` is `unknown` for exactly this reason. Sending JSON rather than
 * a bare status still costs nothing and gives the caller something to show.
 */
@Provider
class CatalogueFailureMapper : ExceptionMapper<CatalogueFailure> {
    override fun toResponse(failure: CatalogueFailure): Response =
        Response
            .status(statusOf(failure))
            .entity(mapOf("message" to failure.message))
            .build()

    private fun statusOf(failure: CatalogueFailure) =
        when (failure) {
            is CatalogueFailure.UnknownShelf, is CatalogueFailure.UnknownBook -> Response.Status.NOT_FOUND
            is CatalogueFailure.AlreadyOnShelf -> Response.Status.CONFLICT
        }
}
