package me.ahoo.wow.openapi

import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class OpenAPIBuilderTest {
    @Test
    fun build() {
        val openApiBuilder = OpenAPIBuilder(MaterializedNamedBoundedContext("test")).addLocalAggregateRouteSpec().build()
        assertThat(openApiBuilder, notNullValue())
    }
}
