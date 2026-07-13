package me.ahoo.wow.spring.boot.starter.openapi

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.SpecVersion
import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.openapi.contract.BuiltInHttpRoutePaths
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class OpenAPIAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun `should load context with openapi router specs`() {
        contextRunner
            .enableWow()
            .withUserConfiguration(
                OpenAPIAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(RouterSpecs::class.java)
                    .hasSingleBean(WowOpenApiCustomizer::class.java)
            }
    }

    @Test
    fun `should customize openapi from router specs`() {
        val openAPI = OpenAPI()
        val routerSpecs = RouterSpecs(MOCK_AGGREGATE_METADATA, routeContributors = emptyList())

        WowOpenApiCustomizer(routerSpecs).customise(openAPI)

        openAPI.specVersion.assert().isEqualTo(SpecVersion.V31)
        openAPI.info.title.assert().isEqualTo(MOCK_AGGREGATE_METADATA.contextName)
        openAPI.paths.assert().isNotNull()
        openAPI.components.assert().isNotNull()
    }

    @Test
    fun `should expose BI OpenAPI operation only when BI route is enabled`() {
        listOf(false, true).forEach { enabled ->
            contextRunner
                .enableWow()
                .withPropertyValues("wow.bi.script.enabled=$enabled")
                .withUserConfiguration(OpenAPIAutoConfiguration::class.java)
                .run { context: AssertableApplicationContext ->
                    val openAPI = OpenAPI()
                    context.getBean(WowOpenApiCustomizer::class.java).customise(openAPI)

                    openAPI.paths.containsKey(BuiltInHttpRoutePaths.Global.BI_SCRIPT)
                        .assert().isEqualTo(enabled)
                }
        }
    }
}
