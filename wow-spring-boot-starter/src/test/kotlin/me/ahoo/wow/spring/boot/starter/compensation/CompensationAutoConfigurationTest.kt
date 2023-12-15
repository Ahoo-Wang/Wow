package me.ahoo.wow.spring.boot.starter.compensation

import io.mockk.mockk
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.compensation.core.CompensationFilter
import me.ahoo.wow.compensation.core.CompensationSaga
import me.ahoo.wow.event.compensation.DomainEventCompensator
import me.ahoo.wow.event.compensation.StateEventCompensator
import me.ahoo.wow.spring.boot.starter.enableWow
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class CompensationAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .enableWow()
            .withBean(CommandBus::class.java, { InMemoryCommandBus() })
            .withBean(DomainEventCompensator::class.java, { mockk<DomainEventCompensator>() })
            .withBean(StateEventCompensator::class.java, { mockk<StateEventCompensator>() })
            .withUserConfiguration(
                CompensationAutoConfiguration::class.java
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(CompensationFilter::class.java)
                    .hasSingleBean(CompensationSaga::class.java)
            }
    }
}
