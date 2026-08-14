package dk.lldata.tsclientgen.emit

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import dk.lldata.tsclientgen.GeneratorOptions
import dk.lldata.tsclientgen.Scalar
import dk.lldata.tsclientgen.fixture.BookDto
import dk.lldata.tsclientgen.fixture.BorrowAccepted
import dk.lldata.tsclientgen.fixture.BorrowOutcome
import dk.lldata.tsclientgen.fixture.BorrowRefused
import dk.lldata.tsclientgen.fixture.BorrowResponse
import dk.lldata.tsclientgen.fixture.Condition
import dk.lldata.tsclientgen.fixture.Copies
import dk.lldata.tsclientgen.fixture.Isbn
import dk.lldata.tsclientgen.fixture.SortField
import dk.lldata.tsclientgen.testOptions
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.typeOf

private data class Defaulted(
    val required: String,
    val withDefault: String = "x",
    val nullableWithDefault: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
private data class OmitsNulls(
    val always: String,
    val sometimes: String? = null,
)

private enum class JsonValueIsNotScalar {
    ONLY,
    ;

    @JsonValue
    fun jsonValue(): List<String> = listOf("nope")
}

private sealed interface UnmappedShape {
    val kind: Condition
}

private data class Dot(
    override val kind: Condition = Condition.NEW,
) : UnmappedShape

class TypeScriptTypesTest {
    private val types = TypeScriptTypes(testOptions)

    private fun typeOfProperty(
        klass: KClass<*>,
        name: String,
    ) = klass.memberProperties.first { it.name == name }.returnType

    @Test
    fun `renders kotlin scalars as their TypeScript spelling`() {
        types.render(typeOf<String>()) shouldBe "string"
        types.render(typeOf<Int>()) shouldBe "number"
        types.render(typeOf<Long>()) shouldBe "number"
        types.render(typeOf<Double>()) shouldBe "number"
        types.render(typeOf<Boolean>()) shouldBe "boolean"
    }

    /**
     * ISO-8601 on the wire, because Quarkus turns off write-dates-as-timestamps — but named, so the
     * client can still say which scalar it is. A value class gets exactly this treatment already.
     */
    @Test
    fun `names the java time types rather than inlining them as string`() {
        types.render(typeOf<java.time.Instant>()) shouldBe "Instant"
        types.render(typeOf<java.time.LocalDate>()) shouldBe "LocalDate"
        types.reachable(listOf(typeOf<java.time.Instant>())).namedScalars.map { it.reference } shouldBe listOf("Instant")
    }

    /** The other position, and one line to adopt. */
    @Test
    fun `inlines every scalar when the project asks for it`() {
        val inline = TypeScriptTypes(testOptions.copy(scalars = GeneratorOptions.INLINE_SCALARS))

        inline.render(typeOf<java.time.Instant>()) shouldBe "string"
        inline.reachable(listOf(typeOf<java.time.Instant>())).namedScalars shouldBe emptyList()
    }

    /** A brand is the third position: same JSON, but a bare string will not do. */
    @Test
    fun `brands a scalar when the project asks for it`() {
        val branded =
            TypeScriptTypes(
                testOptions.copy(scalars = testOptions.scalars + ("java.time.Instant" to Scalar.branded("Instant", "string"))),
            )

        branded.render(typeOf<java.time.Instant>()) shouldBe "Instant"
        branded
            .reachable(listOf(typeOf<java.time.Instant>()))
            .namedScalars
            .single()
            .declaration shouldBe
            """string & { readonly __brand: "Instant" }"""
    }

    /** Reached one property deep, by the same walk that finds the DTOs — not a second traversal. */
    @Test
    fun `finds a named scalar nested inside a payload`() {
        types.reachable(listOf(typeOf<BookDto>())).namedScalars.map { it.reference } shouldBe listOf("Instant")
    }

    @Test
    fun `renders a nullable as a union rather than an optional`() {
        types.renderNullable(typeOf<String?>()) shouldBe "string | null"
    }

    /** `A | null[]` would be an array of nulls unioned with A — the parentheses are load-bearing. */
    @Test
    fun `parenthesises a nullable element type inside an array`() {
        types.renderNullable(typeOf<List<String?>>()) shouldBe "(string | null)[]"
        types.renderNullable(typeOf<List<String>>()) shouldBe "string[]"
    }

    @Test
    fun `renders a map as a Record`() {
        types.render(typeOf<Map<String, Int>>()) shouldBe "Record<string, number>"
    }

    /** A type outside `dtoPackages` with no scalar spelling is not something to declare. */
    @Test
    fun `treats only the configured packages as payload types`() {
        types.isDto(typeOf<BookDto>()) shouldBe true
        types.isDto(typeOf<java.util.Locale>()) shouldBe false
        types.isDto(typeOf<String>()) shouldBe false
    }

    /**
     * The rule the whole generator turns on: Jackson writes a defaulted field regardless, so a
     * default says what is written, not whether it is written. Reading it as "optional" would mark
     * most of the response surface optional and make every consumer null-check fields that are
     * always there.
     */
    @Test
    fun `a response field with a default is still always present`() {
        types.isOptionalResponseField(Defaulted::class, typeOfProperty(Defaulted::class, "withDefault")) shouldBe false
        types.isOptionalResponseField(Defaulted::class, typeOfProperty(Defaulted::class, "nullableWithDefault")) shouldBe false
    }

    /** Only `@JsonInclude(NON_NULL)` can drop a field, and then only a nullable one. */
    @Test
    fun `a response field is optional only when the class omits nulls and the field is nullable`() {
        types.isOptionalResponseField(OmitsNulls::class, typeOfProperty(OmitsNulls::class, "sometimes")) shouldBe true
        types.isOptionalResponseField(OmitsNulls::class, typeOfProperty(OmitsNulls::class, "always")) shouldBe false
    }

    /** Requests read the other way round: the caller may omit whatever Kotlin can default. */
    @Test
    fun `a request field is optional exactly when it has a kotlin default`() {
        types.isOptionalRequestField(Defaulted::class, "withDefault") shouldBe true
        types.isOptionalRequestField(Defaulted::class, "nullableWithDefault") shouldBe true
        types.isOptionalRequestField(Defaulted::class, "required") shouldBe false
    }

    /**
     * Jackson writes an enum as a scalar, so the client has to compare it against a string. An
     * object type would still compile at the call site — `book.condition.wireValue` — while reading
     * `undefined`, which is the one failure mode a generated client must not have.
     */
    @Test
    fun `spells an enum as the literals Jackson writes`() {
        fun spelled(klass: KClass<*>) = types.enumWireLiterals(klass).map { it.toString() }

        spelled(SortField::class) shouldBe listOf("\"TITLE\"", "\"AUTHOR\"")
        spelled(Condition::class) shouldBe listOf("\"new\"", "\"used\"")
        spelled(BorrowOutcome::class) shouldBe listOf("\"accepted\"", "\"refused\"")
    }

    /** Emitting a plausible spelling for an unreadable `@JsonValue` is worse than refusing. */
    @Test
    fun `refuses a JsonValue with no literal spelling`() {
        val failure = assertThrows<IllegalStateException> { types.enumWireLiterals(JsonValueIsNotScalar::class) }

        failure.message!! shouldContain "no TypeScript literal spelling"
    }

    /** The enum is still declared; what must not happen is walking into `name`/`ordinal`. */
    @Test
    fun `reaches an enum without pulling in its JVM members`() {
        types.isEnum(Condition::class) shouldBe true
        types.reachableDtos(listOf(typeOf<Condition>())) shouldBe listOf(Condition::class)
    }

    /**
     * Same failure as the enum one, different Kotlin feature: Jackson unwraps a value class, so
     * `{ value: string }` would let `book.isbn.value` compile against a bare `"978-…"` and read
     * `undefined`.
     */
    @Test
    fun `unwraps a value class to the scalar Jackson writes`() {
        types.isValueClass(Isbn::class) shouldBe true
        types.render(types.valueClassUnderlyingType(Isbn::class)) shouldBe "string"
        types.render(types.valueClassUnderlyingType(Copies::class)) shouldBe "number"
    }

    /** A plain data class with one property is not a value class and keeps its object shape. */
    @Test
    fun `does not mistake a single-field data class for a value class`() {
        types.isValueClass(Defaulted::class) shouldBe false
        types.isEnum(Defaulted::class) shouldBe false
    }

    /**
     * A sealed base has no shape of its own on the wire — it is whichever variant was written. The
     * variant/literal mapping has to come from `@JsonSubTypes`, because reflection can list the
     * subclasses but cannot read the value a variant fixes its discriminator to.
     */
    @Test
    fun `reads a sealed union's discriminator and arms`() {
        types.isSealedUnion(BorrowResponse::class) shouldBe true
        types.unionDiscriminatorProperty(BorrowResponse::class) shouldBe "outcome"
        types.unionVariants(BorrowResponse::class) shouldBe
            listOf(BorrowAccepted::class to "accepted", BorrowRefused::class to "refused")
    }

    /**
     * The variants are what get declared; the base only names them. The discriminator enum comes
     * first because each variant refers to it — declaration order needs no forward references.
     */
    @Test
    fun `reaches a sealed union through its variants`() {
        types.reachableDtos(listOf(typeOf<BorrowResponse>())) shouldBe
            listOf(BorrowOutcome::class, BorrowAccepted::class, BorrowRefused::class, BorrowResponse::class)
    }

    /**
     * Refusing is the whole point: falling back to the base's own members emits `{ outcome }` with
     * every variant's payload missing, and every call site still compiles.
     */
    @Test
    fun `refuses a sealed type with no subtype mapping`() {
        val failure = assertThrows<IllegalStateException> { types.unionDiscriminatorProperty(UnmappedShape::class) }

        failure.message!! shouldContain "no @JsonTypeInfo"
    }
}
