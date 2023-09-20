package me.ahoo.wow.openapi

import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class RouterSpecsTest {
    @Test
    fun build() {
        val routerSpecs = RouterSpecs(MaterializedNamedBoundedContext("test")).build()
        assertThat(routerSpecs, notNullValue())
    }
}
