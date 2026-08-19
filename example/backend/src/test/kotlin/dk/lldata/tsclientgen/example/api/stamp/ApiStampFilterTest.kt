package dk.lldata.tsclientgen.example.api.stamp

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The decision, without a server. What a real deployment does with the line — log it, count it,
 * tag a trace — is a project's choice; that there is a line, once, is this filter's.
 */
class ApiStampFilterTest {
    private val filter = ApiStampFilter()

    @Test
    fun `says nothing about a client generated against this backend`() {
        filter.report(ApiStamp.current).shouldBeNull()
    }

    /** Absent rather than wrong: curl, a health check, anything that is not a generated client. */
    @Test
    fun `says nothing about a caller that sends no stamp`() {
        filter.report(null).shouldBeNull()
    }

    @Test
    fun `names both stamps when they disagree`() {
        val warning = filter.report("deadbeef")!!

        warning shouldContain "deadbeef"
        warning shouldContain ApiStamp.current
    }

    /**
     * A stale client calls constantly. Without this the warning is a log flood, and the thousandth
     * copy carries nothing the first did not.
     */
    @Test
    fun `reports each stamp once, however many calls carry it`() {
        filter.report("deadbeef")

        filter.report("deadbeef").shouldBeNull()
        // A different stale client is still worth hearing about.
        filter.report("d00dfeed")!! shouldContain "d00dfeed"
    }

    /** The stamp on the classpath is the one the client carries, or the comparison is meaningless. */
    @Test
    fun `reads the stamp the generator wrote`() {
        ApiStamp.current shouldBe
            ApiStamp::class.java
                .getResource("/api-stamp.txt")!!
                .readText()
                .trim()
    }
}
