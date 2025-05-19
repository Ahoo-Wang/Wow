package me.ahoo.wow.openapi

import io.swagger.v3.oas.models.OpenAPI
import me.ahoo.test.asserts.assert
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import org.junit.jupiter.api.Test

class RouterSpecsTest {
    @Test
    fun build() {
        val routerSpecs = RouterSpecs(MaterializedNamedBoundedContext("test")).build()
        routerSpecs.assert().isNotEmpty()
    }

    @Test
    fun mergeOpenAPI() {
        val openAPI = OpenAPI()
        RouterSpecs(MaterializedNamedBoundedContext("test")).build()
            .mergeOpenAPI(openAPI)
        openAPI.components.schemas.assert().isNotEmpty()
    }
}
