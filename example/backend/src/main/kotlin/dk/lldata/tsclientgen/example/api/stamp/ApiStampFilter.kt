package dk.lldata.tsclientgen.example.api.stamp

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Warns when a caller's client was generated against a different API than this backend serves.
 *
 * This is the half a client cannot do for itself. It knows its own stamp and sends it; only the
 * server that minted one knows what the current stamp is, so only the server can see the two
 * disagree — and it sees it on the first call rather than on the call that happens to break.
 *
 * A demonstration of the shape, not a component to depend on: the generator writes the value and
 * nothing else, and a real project's version of this might raise a metric, tag a trace, or say
 * nothing below some age instead.
 */
@Provider
class ApiStampFilter : ContainerRequestFilter {
    /**
     * One line per distinct stamp, not per request. A stale client calls constantly, and a warning
     * on every call is a log flood rather than a signal — the second thousand requests carry no
     * information the first did not.
     */
    private val reported = ConcurrentHashMap.newKeySet<String>()

    override fun filter(context: ContainerRequestContext) {
        report(context.getHeaderString(ApiStamp.HEADER))?.let { log.warn(it) }
    }

    /** The line worth logging, or null — including when this stamp has already been reported. */
    internal fun report(sent: String?): String? {
        val mismatch = ApiStamp.mismatch(sent) ?: return null
        return if (reported.add(sent)) mismatch else null
    }

    private companion object {
        private val log: Logger = Logger.getLogger(ApiStampFilter::class.java)
    }
}
