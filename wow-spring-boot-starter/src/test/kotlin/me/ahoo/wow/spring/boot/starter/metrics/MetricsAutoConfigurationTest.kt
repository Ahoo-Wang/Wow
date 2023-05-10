package me.ahoo.wow.spring.boot.starter.metrics

import me.ahoo.wow.spring.boot.starter.enableWow
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class MetricsAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .enableWow()
            .withUserConfiguration(
                MetricsAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(MetricsBeanPostProcessor::class.java)
            }
    }

    @Test
    fun contextLoadsIfDisabled() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${ConditionalOnMetricsEnabled.ENABLED_KEY}=false",
            )
            .withUserConfiguration(
                MetricsAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .doesNotHaveBean(MetricsBeanPostProcessor::class.java)
            }
    }
}