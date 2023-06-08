package me.ahoo.wow.spring.boot.starter.eventsourcing.state

import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.eventsourcing.state.SendStateEventFilter
import me.ahoo.wow.spring.boot.starter.BusProperties
import me.ahoo.wow.spring.boot.starter.enableWow
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class StateAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${StateProperties.BUS_TYPE}=${BusProperties.Type.IN_MEMORY_NAME}",
            )
            .withUserConfiguration(
                StateAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(InMemoryStateEventBus::class.java)
                    .hasSingleBean(SendStateEventFilter::class.java)
            }
    }
}
