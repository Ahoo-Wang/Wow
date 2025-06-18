package me.ahoo.wow.openapi

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.info.Info
import me.ahoo.test.asserts.assert
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import org.junit.jupiter.api.Test

class RouterSpecsTest {
    val materializedNamedBoundedContext = MaterializedNamedBoundedContext("test")

    @Test
    fun build() {
        val routerSpecs = RouterSpecs(materializedNamedBoundedContext).build()
        routerSpecs.assert().isNotEmpty()
    }

    @Test
    fun mergeOpenAPI() {
        val openAPI = OpenAPI()
        RouterSpecs(materializedNamedBoundedContext).build()
            .mergeOpenAPI(openAPI)
        openAPI.info?.title.assert().isEqualTo(materializedNamedBoundedContext.contextName)
        openAPI.components.schemas.assert().isNotEmpty()
    }

    @Test
    fun mergeOpenAPIWithInfo() {
        val info = Info()
        val openAPI = OpenAPI().info(info)
        RouterSpecs(materializedNamedBoundedContext).build()
            .mergeOpenAPI(openAPI)
        openAPI.info.assert().isSameAs(info)
        openAPI.components.schemas.assert().isNotEmpty()
    }

    @Test
    fun mergeOpenAPIWithNotNull() {
        val info = Info()
        val paths = Paths()
        val components = Components()
        val openAPI = OpenAPI().info(info).paths(paths).components(components)
        RouterSpecs(materializedNamedBoundedContext).build()
            .mergeOpenAPI(openAPI)
        openAPI.info.assert().isSameAs(info)
        openAPI.paths.assert().isSameAs(paths)
        openAPI.components.assert().isSameAs(components)
        openAPI.components.schemas.assert().isNotEmpty()
    }
}
