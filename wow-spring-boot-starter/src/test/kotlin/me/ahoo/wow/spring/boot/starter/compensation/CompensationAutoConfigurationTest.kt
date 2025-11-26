package me.ahoo.wow.spring.boot.starter.compensation

import io.mockk.mockk
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.compensation.core.CompensationEventProcessor
import me.ahoo.wow.compensation.core.DomainEventCompensationFilter
import me.ahoo.wow.compensation.core.StateEventCompensationFilter
import me.ahoo.wow.event.compensation.DomainEventCompensator
import me.ahoo.wow.event.compensation.StateEventCompensator
import me.ahoo.wow.messaging.compensation.EventCompensateSupporter
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
            .withBean(CommandGateway::class.java, { mockk() })
            .withBean(DomainEventCompensator::class.java, { mockk() })
            .withBean(StateEventCompensator::class.java, { mockk() })
            .withUserConfiguration(
                CompensationAutoConfiguration::class.java
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(EventCompensateSupporter::class.java)
                    .hasSingleBean(DomainEventCompensationFilter::class.java)
                    .hasSingleBean(StateEventCompensationFilter::class.java)
                    .hasSingleBean(CompensationEventProcessor::class.java)
            }
    }
}
