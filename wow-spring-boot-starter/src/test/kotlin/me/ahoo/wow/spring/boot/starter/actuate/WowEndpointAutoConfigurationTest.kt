package me.ahoo.wow.spring.boot.starter.actuate

import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.kafka.KafkaAutoConfiguration
import me.ahoo.wow.spring.boot.starter.kafka.KafkaProperties
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class WowEndpointAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .enableWow()
            .withPropertyValues("${KafkaProperties.PREFIX}.bootstrap-servers=kafka")
            .withUserConfiguration(
                WowEndpointAutoConfiguration::class.java,
                KafkaAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(WowEndpoint::class.java)
                    .hasSingleBean(WowBIEndpoint::class.java)

                AssertionsForInterfaceTypes.assertThat(context).getBean(WowBIEndpoint::class.java).extracting {
                    assertThat(it.generate(), notNullValue())
                }
            }
    }
}
