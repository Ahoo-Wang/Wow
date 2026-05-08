package me.ahoo.wow.spring.boot.starter.openapi

import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.spring.boot.starter.enableWow
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
}
