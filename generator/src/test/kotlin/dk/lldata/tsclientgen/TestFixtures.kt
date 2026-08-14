package dk.lldata.tsclientgen

import dk.lldata.tsclientgen.fixture.ApiKeyedResource
import dk.lldata.tsclientgen.fixture.BooksResource
import dk.lldata.tsclientgen.fixture.CommentsResource
import dk.lldata.tsclientgen.fixture.OpsResource
import dk.lldata.tsclientgen.model.ApiModel
import dk.lldata.tsclientgen.model.ApiModelReader

/** What every test reads: the fixture package, with no rule left at its default that matters. */
val testOptions =
    GeneratorOptions(
        dtoPackages = listOf("dk.lldata.tsclientgen.fixture"),
        header = "Generated. Do not edit.",
    )

/**
 * The fixture resources as a model, named explicitly rather than walked off disk: the fixtures
 * include shapes that only exist to be refused, and a directory walk would drag them into every
 * test that only wants the good ones.
 */
fun fixtureModel(vararg classes: Class<*> = DEFAULT_FIXTURES): ApiModel = ApiModelReader(testOptions).read(classes.toList())

private val DEFAULT_FIXTURES: Array<Class<*>> =
    arrayOf(
        BooksResource::class.java,
        CommentsResource::class.java,
        OpsResource::class.java,
    )

val ALL_FIXTURES: Array<Class<*>> = DEFAULT_FIXTURES + ApiKeyedResource::class.java
