package me.ahoo.wow.openapi

import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class RouterTest {
    @Test
    fun build() {
        val router = Router(MaterializedNamedBoundedContext("test")).addLocalAggregateRouteSpec().build()
        assertThat(router, notNullValue())
    }
}
