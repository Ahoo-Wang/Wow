package me.ahoo.wow.spring.boot.starter.openapi

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.SpecVersion
import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.rest.RoutePaths
import me.ahoo.wow.spring.boot.starter.bi.BiAutoConfiguration
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.Ordered
import java.util.function.Supplier

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
    fun `should keep route catalog when OpenAPI documentation is disabled`() {
        contextRunner
            .enableWow()
            .withPropertyValues("wow.openapi.enabled=false")
            .withUserConfiguration(OpenAPIAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasNotFailed()
                    .hasSingleBean(RouterSpecs::class.java)
                    .doesNotHaveBean("wowOpenApiCustomizer")
            }
    }

    @Test
    fun `should generate schemas at startup only when the document is served`() {
        contextRunner
            .enableWow()
            .withUserConfiguration(OpenAPIAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                context.getBean(OpenAPIComponentContext::class.java).responses.assert().isNotEmpty()
            }
        contextRunner
            .enableWow()
            .withPropertyValues("wow.openapi.enabled=false")
            .withUserConfiguration(OpenAPIAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                context.getBean(RouterSpecs::class.java).toRouteCatalog().routes.assert().isNotEmpty()
                val componentContext = context.getBean(OpenAPIComponentContext::class.java)
                componentContext.responses.assert().isEmpty()
                componentContext.schemas.assert().isEmpty()
            }
        contextRunner
            .enableWow()
            .withClassLoader(FilteredClassLoader("org.springdoc.core.customizers.OpenApiCustomizer"))
            .withUserConfiguration(OpenAPIAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                val componentContext = context.getBean(OpenAPIComponentContext::class.java)
                componentContext.responses.assert().isEmpty()
                componentContext.schemas.assert().isEmpty()
            }
    }

    @Test
    fun `should keep route catalog without Springdoc`() {
        contextRunner
            .enableWow()
            .withClassLoader(FilteredClassLoader("org.springdoc.core.customizers.OpenApiCustomizer"))
            .withUserConfiguration(OpenAPIAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasNotFailed()
                    .hasSingleBean(RouterSpecs::class.java)
                    .doesNotHaveBean("wowOpenApiCustomizer")
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
    fun `should expose BI OpenAPI operation by default and honor explicit disable`() {
        listOf(null, false, true).forEach { enabled ->
            val runner = if (enabled == null) {
                contextRunner
            } else {
                contextRunner.withPropertyValues("wow.bi.script.enabled=$enabled")
            }
            runner
                .enableWow()
                .withPropertyValues("wow.webflux.enabled=false")
                .withUserConfiguration(OpenAPIAutoConfiguration::class.java, BiAutoConfiguration::class.java)
                .run { context: AssertableApplicationContext ->
                    val openAPI = OpenAPI()
                    context.getBean(WowOpenApiCustomizer::class.java).customise(openAPI)

                    openAPI.paths.containsKey(RoutePaths.BI_SCRIPT)
                        .assert().isEqualTo(enabled != false)
                }
        }
    }

    @Test
    fun `should apply the document filters in their order after merging Wow's routes`() {
        val applied = mutableListOf<String>()

        class NamedFilter(private val name: String, private val order: Int) : OpenApiDocumentFilter, Ordered {
            override fun filter(openApi: OpenAPI) {
                // Wow's routes are already merged.
                openApi.paths.assert().isNotEmpty()
                applied += name
            }

            override fun getOrder(): Int = order
        }
        contextRunner
            .enableWow()
            .withBean("second", OpenApiDocumentFilter::class.java, Supplier { NamedFilter("second", 2) })
            .withBean("first", OpenApiDocumentFilter::class.java, Supplier { NamedFilter("first", 1) })
            .withUserConfiguration(OpenAPIAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                context.getBean(WowOpenApiCustomizer::class.java).customise(OpenAPI())
                applied.assert().containsExactly("first", "second")
            }
    }

    @Test
    fun `should leave the BI route out without wow-bi on the classpath`() {
        contextRunner
            .enableWow()
            .withClassLoader(FilteredClassLoader("me.ahoo.wow.bi."))
            .withUserConfiguration(OpenAPIAutoConfiguration::class.java, BiAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                context.assert().hasNotFailed()
                context.getBean(RouterSpecs::class.java).toRouteCatalog().routes.map { it.path }.assert()
                    .doesNotContain(RoutePaths.BI_SCRIPT)
                val openAPI = OpenAPI()
                context.getBean(WowOpenApiCustomizer::class.java).customise(openAPI)
                openAPI.paths.containsKey(RoutePaths.BI_SCRIPT).assert().isFalse()
            }
    }
}
