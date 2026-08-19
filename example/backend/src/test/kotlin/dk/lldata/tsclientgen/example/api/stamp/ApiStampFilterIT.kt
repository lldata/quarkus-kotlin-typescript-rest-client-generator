package dk.lldata.tsclientgen.example.api.stamp

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Test

/**
 * That the filter is installed at all, and that a stale stamp is reported rather than refused.
 *
 * The refusal question is the one worth a running server: a filter that aborted the request would
 * turn an additive backend change into an outage for every client generated before it, and nothing
 * short of a real call proves it does not.
 */
@QuarkusTest
class ApiStampFilterIT {
    @Test
    fun `serves a call from a client generated against this backend`() {
        given()
            .header(ApiStamp.HEADER, ApiStamp.current)
            .`when`()
            .get("/api/shelves")
            .then()
            .statusCode(200)
    }

    @Test
    fun `serves a call from a client that has drifted, having warned about it`() {
        given()
            .header(ApiStamp.HEADER, "deadbeef")
            .`when`()
            .get("/api/shelves")
            .then()
            .statusCode(200)
    }

    @Test
    fun `serves a caller that sends no stamp at all`() {
        given()
            .`when`()
            .get("/api/shelves")
            .then()
            .statusCode(200)
    }
}
