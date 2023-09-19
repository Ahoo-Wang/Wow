package me.ahoo.wow.spring.boot.starter.openapi

import io.swagger.v3.oas.models.OpenAPI
import me.ahoo.wow.openapi.OpenAPIBuilder
import me.ahoo.wow.spring.boot.starter.enableWow
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class OpenAPIAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .enableWow()
            .withUserConfiguration(
                OpenAPIAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(OpenAPIBuilder::class.java)
                    .hasSingleBean(OpenAPI::class.java)
            }
    }
}
