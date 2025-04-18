package me.ahoo.wow.openapi

import me.ahoo.test.asserts.assert
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import org.junit.jupiter.api.Test

class RouterSpecsTest {
    @Test
    fun build() {
        val routerSpecs = RouterSpecs(MaterializedNamedBoundedContext("test")).build()
        routerSpecs.assert().isNotEmpty()
    }
}
