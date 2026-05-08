package me.ahoo.wow.openapi

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.info.Info
import me.ahoo.test.asserts.assert
import me.ahoo.wow.example.api.ExampleService
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.openapi.RouterSpecs.Companion.DEFAULT_OPENAPI_INFO_TITLE
import org.junit.jupiter.api.Test

class RouterSpecsTest {
    val materializedNamedBoundedContext = MaterializedNamedBoundedContext(ExampleService.SERVICE_NAME)

    @Test
    fun `should merge open api when context name not found`() {
        val openAPI = OpenAPI()
        val materializedNamedBoundedContext = MaterializedNamedBoundedContext(generateGlobalId())
        val routerSpecs = RouterSpecs(materializedNamedBoundedContext).build()
        routerSpecs.mergeOpenAPI(openAPI)
        routerSpecs.assert().isNotEmpty()
    }

    @Test
    fun `should merge router specs into open api`() {
        val openAPI = OpenAPI()
        RouterSpecs(materializedNamedBoundedContext).build()
            .mergeOpenAPI(openAPI)
        openAPI.info?.title.assert().isEqualTo(materializedNamedBoundedContext.contextName)
        openAPI.components.schemas.assert().isNotEmpty()
    }

    @Test
    fun `should keep existing info when merging`() {
        val info = Info()
        val openAPI = OpenAPI().info(info)
        RouterSpecs(materializedNamedBoundedContext).build()
            .mergeOpenAPI(openAPI)
        openAPI.info.assert().isSameAs(info)
        openAPI.components.schemas.assert().isNotEmpty()
    }

    @Test
    fun `should replace default info title when merging`() {
        val info = Info().title(DEFAULT_OPENAPI_INFO_TITLE).description("hello")
        val openAPI = OpenAPI().info(info)
        RouterSpecs(materializedNamedBoundedContext).build()
            .mergeOpenAPI(openAPI)
        openAPI.info.assert().isSameAs(info)
        openAPI.components.schemas.assert().isNotEmpty()
    }

    @Test
    fun `should keep custom info title when merging`() {
        val info = Info().title(generateGlobalId())
        val openAPI = OpenAPI().info(info)
        RouterSpecs(materializedNamedBoundedContext).build()
            .mergeOpenAPI(openAPI)
        openAPI.info.assert().isSameAs(info)
        openAPI.components.schemas.assert().isNotEmpty()
    }

    @Test
    fun `should merge into existing open api with all components`() {
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
