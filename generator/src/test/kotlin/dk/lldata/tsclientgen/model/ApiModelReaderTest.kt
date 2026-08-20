package dk.lldata.tsclientgen.model

import dk.lldata.tsclientgen.fixture.BookQuery
import dk.lldata.tsclientgen.fixture.BooksResource
import dk.lldata.tsclientgen.fixture.BorrowResponse
import dk.lldata.tsclientgen.fixture.OpsResource
import dk.lldata.tsclientgen.fixture.SortField
import dk.lldata.tsclientgen.fixtureModel
import dk.lldata.tsclientgen.testOptions
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.reflect.typeOf

class ApiModelReaderTest {
    private val model = fixtureModel()

    private fun endpoint(id: String) = model.endpoints.first { it.id() == id }

    @Test
    fun `reads the verb and the joined path from the resource and the method`() {
        endpoint("BooksResource.detail").verb shouldBe "GET"
        endpoint("BooksResource.detail").path shouldBe "/api/shelf/{shelf}/books/{isbn}"
        endpoint("BooksResource.list").path shouldBe "/api/shelf/{shelf}/books"
    }

    /** Opt-in: a resource without the annotation is in the model and out of the client. */
    @Test
    fun `records whether a resource opted into the client`() {
        endpoint("BooksResource.list").inTypeScriptApi shouldBe true
        endpoint("OpsResource.stats").inTypeScriptApi shouldBe false
        model.clientSurface.none { it.resource == "OpsResource" } shouldBe true
    }

    /** `RestResponse<T>` is a wrapper, and the client cares about the `T` that reaches the wire. */
    @Test
    fun `unwraps a wrapper return type to its payload`() {
        val borrow = endpoint("BooksResource.borrow")

        borrow.responseType shouldBe typeOf<BorrowResponse>()
        borrow.unwrappedFrom shouldBe "org.jboss.resteasy.reactive.RestResponse"
    }

    @Test
    fun `keeps an unwrapped return type as it is`() {
        endpoint("BooksResource.detail").unwrappedFrom.shouldBeNull()
    }

    /** `RestResponse<Void>` and a `Unit` return are the two ways of saying "no body". */
    @Test
    fun `recognises a bodyless response`() {
        ApiModelReader.isBodyless(endpoint("BooksResource.remove").responseType) shouldBe true
        ApiModelReader.isBodyless(endpoint("BooksResource.detail").responseType) shouldBe false
    }

    /** On the wire a `@BeanParam` is not a thing at all — its fields are ordinary query params. */
    @Test
    fun `flattens a bean param into its wire parameters`() {
        val list = endpoint("BooksResource.list")

        list.queryParams.map { it.wireName } shouldContainExactly listOf("q", "sort", "movedFrom", "limit", "page-size")
        list.params.none { it.source == ParamSource.BEAN } shouldBe true
        list.queryParams.first { it.wireName == "sort" }.type shouldBe typeOf<SortField>()
    }

    /**
     * A `@DefaultValue` makes a non-nullable Kotlin parameter optional on the wire, and it is the
     * only form of default reflection can read — a Kotlin default is visible as "exists" and not as
     * "is 20".
     */
    @Test
    fun `reads a server default and the wire name, not the Kotlin name`() {
        val limit = endpoint("BooksResource.list").queryParams.first { it.wireName == "limit" }

        limit.name shouldBe "limitRaw"
        limit.defaultValue shouldBe "20"
        limit.optional shouldBe true
        limit.nullable shouldBe false
    }

    @Test
    fun `a nullable query param is optional without a default`() {
        val lang = endpoint("BooksResource.detail").queryParams.single()

        lang.wireName shouldBe "lang"
        lang.defaultValue.shouldBeNull()
        lang.optional shouldBe true
    }

    /** The one parameter with no source annotation is the body. Everything else is classified. */
    @Test
    fun `classifies each parameter by where it comes from`() {
        val add = endpoint("BooksResource.add")
        add.body!!.name shouldBe "body"
        add.pathParams.map { it.wireName } shouldContainExactly listOf("shelf")

        val remove = endpoint("BooksResource.remove")
        remove.params.map { it.source } shouldContainExactly
            listOf(ParamSource.PATH, ParamSource.PATH, ParamSource.COOKIE, ParamSource.CONTEXT)
        remove.body.shouldBeNull()
    }

    /** The debug print is a diagnostic, so what matters is that it names what a reader looks for. */
    @Test
    fun `describes the model as text`() {
        val described = model.describe()

        described shouldContain "POST   /api/shelf/{shelf}/books/{isbn}/borrow"
        described shouldContain "[via RestResponse]"
        described shouldContain "[not in client]"
        described shouldContain "\"limit\" limitRaw: kotlin.Int = 20"
    }

    /** `clientResources` alone, with the annotation off, is the annotation-free setup. */
    @Test
    fun `puts a named resource on the client surface without the annotation`() {
        val options =
            testOptions.copy(
                scanForAnnotation = false,
                clientResources = listOf(OpsResource::class.java.name),
            )

        val named = ApiModelReader(options).read(listOf(OpsResource::class.java))

        named.clientSurface.map { it.id() } shouldContainExactly listOf("OpsResource.stats")
    }

    /** A resource that is both annotated and named appears once, not twice. */
    @Test
    fun `does not duplicate a resource that is both annotated and named`() {
        val options = testOptions.copy(clientResources = listOf(BooksResource::class.java.name))

        val both = ApiModelReader(options).read(listOf(BooksResource::class.java))

        both.endpoints.count { it.id() == "BooksResource.list" } shouldBe 1
    }

    /** The surface is the union: an annotated resource and a disjoint named one both reach it. */
    @Test
    fun `unions an annotated resource with a disjoint named one`() {
        val options = testOptions.copy(clientResources = listOf(OpsResource::class.java.name))

        val union = ApiModelReader(options).read(listOf(BooksResource::class.java, OpsResource::class.java))

        union.clientSurface.map { it.resource }.toSet() shouldBe setOf("BooksResource", "OpsResource")
    }

    /** A typo, a moved class or a package rename would otherwise silently shrink the client. */
    @Test
    fun `refuses a named resource that does not resolve on the classpath`() {
        val options = testOptions.copy(clientResources = listOf("dk.lldata.tsclientgen.fixture.NoSuchResource"))

        val failure = assertThrows<IllegalArgumentException> { ApiModelReader(options).read(listOf(BooksResource::class.java)) }

        failure.message!! shouldContain "dk.lldata.tsclientgen.fixture.NoSuchResource"
    }

    /** A class with no `@Path` has no endpoints, so naming it can only be the wrong class. */
    @Test
    fun `refuses a named resource with no @Path`() {
        val options = testOptions.copy(clientResources = listOf(BookQuery::class.java.name))

        val failure = assertThrows<IllegalArgumentException> { ApiModelReader(options).read(listOf(BooksResource::class.java)) }

        failure.message!! shouldContain BookQuery::class.java.name
    }

    /** `scanForAnnotation = false` with nothing named can only ever produce an empty client. */
    @Test
    fun `refuses when neither route onto the client surface is open`() {
        val failure =
            assertThrows<IllegalArgumentException> {
                testOptions.copy(scanForAnnotation = false, clientResources = emptyList())
            }

        failure.message!! shouldContain "scanForAnnotation"
    }
}
