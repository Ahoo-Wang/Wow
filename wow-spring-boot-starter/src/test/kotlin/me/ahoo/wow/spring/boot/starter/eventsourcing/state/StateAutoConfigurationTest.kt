package me.ahoo.wow.spring.boot.starter.eventsourcing.state

import io.mockk.mockk
import me.ahoo.wow.event.compensation.StateEventCompensator
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.state.DistributedStateEventBus
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.eventsourcing.state.LocalFirstStateEventBus
import me.ahoo.wow.eventsourcing.state.LocalStateEventBus
import me.ahoo.wow.eventsourcing.state.SendStateEventFilter
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.spring.boot.starter.BusType
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class StateAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()

    @Test
    fun `should load context when in-memory state event bus`() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${StateProperties.BUS_TYPE}=${BusType.IN_MEMORY_NAME}",
            )
            .withBean(EventStore::class.java, { InMemoryEventStore() })
            .withBean(StateAggregateFactory::class.java, { ConstructorStateAggregateFactory })
            .withUserConfiguration(
                StateAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(InMemoryStateEventBus::class.java)
                    .hasSingleBean(StateEventCompensator::class.java)
                    .hasSingleBean(SendStateEventFilter::class.java)
                    .hasSingleBean(StateEventCompensator::class.java)
            }
    }

    @Test
    fun `should load context when local-first state event bus`() {
        contextRunner
            .enableWow()
            .withBean(DistributedStateEventBus::class.java, { mockk() })
            .withBean(EventStore::class.java, { InMemoryEventStore() })
            .withBean(StateAggregateFactory::class.java, { ConstructorStateAggregateFactory })
            .withUserConfiguration(
                StateAutoConfiguration::class.java,
            )
            .withUserConfiguration(
                StateAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(LocalStateEventBus::class.java)
                    .hasSingleBean(LocalFirstStateEventBus::class.java)
            }
    }
}
