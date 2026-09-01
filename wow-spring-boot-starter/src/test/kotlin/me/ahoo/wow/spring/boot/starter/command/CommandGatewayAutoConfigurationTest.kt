package me.ahoo.wow.spring.boot.starter.command

import io.mockk.mockk
import io.mockk.verify
import jakarta.validation.Validator
import me.ahoo.cosid.machine.HostAddressSupplier
import me.ahoo.cosid.machine.LocalHostAddressSupplier
import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.command.RequestIdChecker
import me.ahoo.wow.command.wait.CommandWaitEndpoint
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.WaitCoordinator
import me.ahoo.wow.eventsourcing.RequestIdExistenceChecker
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.infra.idempotency.AggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.IdempotencyChecker
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.spring.boot.starter.BusType
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.kafka.KafkaAutoConfiguration
import me.ahoo.wow.spring.boot.starter.kafka.KafkaProperties
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.beans.factory.support.AbstractBeanDefinition
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.util.concurrent.atomic.AtomicInteger

class CommandGatewayAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun `should retain direct command bus factory method`() {
        val directFactoryMethod = CommandGatewayAutoConfiguration::class.java.methods.singleOrNull { method ->
            method.name == "commandGateway" &&
                method.parameterTypes.contentEquals(
                    arrayOf(
                        CommandWaitEndpoint::class.java,
                        CommandBus::class.java,
                        Validator::class.java,
                        RequestIdChecker::class.java,
                        WaitCoordinator::class.java,
                        CommandWaitNotifier::class.java,
                    ),
                )
        }

        directFactoryMethod.assert().isNotNull()
        directFactoryMethod!!.returnType.assert().isEqualTo(CommandGateway::class.java)
    }

    @Test
    fun `should fail without self reference when no command bus is available`() {
        contextRunner
            .enableWow()
            .withBean(CommandWaitNotifier::class.java, { mockk<CommandWaitNotifier>() })
            .withBean(HostAddressSupplier::class.java, { LocalHostAddressSupplier.INSTANCE })
            .withPropertyValues(
                "${CommandProperties.BUS_TYPE}=${BusType.KAFKA_NAME}",
                "${KafkaProperties.PREFIX}.enabled=false",
            ).withConfiguration(
                AutoConfigurations.of(
                    KafkaAutoConfiguration::class.java,
                    CommandAutoConfiguration::class.java,
                    CommandGatewayAutoConfiguration::class.java,
                ),
            ).run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNotNull()
                generateSequence(context.startupFailure) { error -> error.cause }
                    .mapNotNull { error -> error.message }
                    .joinToString("\n")
                    .assert()
                    .contains("A backing CommandBus is required to create CommandGateway.")
                    .doesNotContain("currently in creation")
            }
    }

    @Test
    fun `should fail without self reference when command bus is not an autowire candidate`() {
        contextRunner
            .enableWow()
            .withBean(CommandWaitNotifier::class.java, { mockk<CommandWaitNotifier>() })
            .withBean(HostAddressSupplier::class.java, { LocalHostAddressSupplier.INSTANCE })
            .withBean(
                "commandBus",
                CommandBus::class.java,
                { InMemoryCommandBus() },
                { beanDefinition -> beanDefinition.isAutowireCandidate = false },
            ).withUserConfiguration(
                CommandAutoConfiguration::class.java,
                CommandGatewayAutoConfiguration::class.java,
            ).run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNotNull()
                generateSequence(context.startupFailure) { error -> error.cause }
                    .mapNotNull { error -> error.message }
                    .joinToString("\n")
                    .assert()
                    .contains("A backing CommandBus is required to create CommandGateway.")
                    .doesNotContain("currently in creation")
            }
    }

    @Test
    fun `should fail without self reference when command bus is not a default candidate`() {
        contextRunner
            .enableWow()
            .withBean(CommandWaitNotifier::class.java, { mockk<CommandWaitNotifier>() })
            .withBean(HostAddressSupplier::class.java, { LocalHostAddressSupplier.INSTANCE })
            .withBean(
                "commandBus",
                CommandBus::class.java,
                { InMemoryCommandBus() },
                { beanDefinition ->
                    (beanDefinition as AbstractBeanDefinition).isDefaultCandidate = false
                },
            ).withUserConfiguration(
                CommandAutoConfiguration::class.java,
                CommandGatewayAutoConfiguration::class.java,
            ).run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNotNull()
                generateSequence(context.startupFailure) { error -> error.cause }
                    .mapNotNull { error -> error.message }
                    .joinToString("\n")
                    .assert()
                    .contains("A backing CommandBus is required to create CommandGateway.")
                    .doesNotContain("currently in creation")
            }
    }

    @Test
    fun `should create primary command gateway for command bus from later auto configuration`() {
        contextRunner
            .enableWow()
            .withBean(CommandWaitNotifier::class.java, { mockk<CommandWaitNotifier>() })
            .withBean(HostAddressSupplier::class.java, { LocalHostAddressSupplier.INSTANCE })
            .withConfiguration(
                AutoConfigurations.of(
                    CommandAutoConfiguration::class.java,
                    CommandGatewayAutoConfiguration::class.java,
                    LateCommandBusAutoConfiguration::class.java,
                ),
            ).run { context: AssertableApplicationContext ->
                context.assert()
                    .hasNotFailed()
                    .hasSingleBean(CommandGateway::class.java)
                context.getBean(CommandBus::class.java)
                    .assert()
                    .isInstanceOf(CommandGateway::class.java)
            }
    }

    @Test
    fun `should create command gateway with resolvable command bus dependency`() {
        contextRunner
            .enableWow()
            .withBean(CommandWaitNotifier::class.java, { mockk<CommandWaitNotifier>() })
            .withBean(HostAddressSupplier::class.java, { LocalHostAddressSupplier.INSTANCE })
            .withInitializer { context ->
                context.beanFactory.registerResolvableDependency(CommandBus::class.java, InMemoryCommandBus())
            }.withUserConfiguration(
                CommandAutoConfiguration::class.java,
                CommandGatewayAutoConfiguration::class.java,
            ).run { context: AssertableApplicationContext ->
                context.assert()
                    .hasNotFailed()
                    .hasSingleBean(CommandGateway::class.java)
            }
    }

    @Test
    fun `should create prototype backing command bus only once`() {
        val creationCount = AtomicInteger()
        contextRunner
            .enableWow()
            .withBean(CommandWaitNotifier::class.java, { mockk<CommandWaitNotifier>() })
            .withBean(HostAddressSupplier::class.java, { LocalHostAddressSupplier.INSTANCE })
            .withBean(
                "commandBus",
                CommandBus::class.java,
                {
                    creationCount.incrementAndGet()
                    InMemoryCommandBus()
                },
                { beanDefinition -> beanDefinition.scope = ConfigurableBeanFactory.SCOPE_PROTOTYPE },
            ).withUserConfiguration(
                CommandAutoConfiguration::class.java,
                CommandGatewayAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasNotFailed()
                    .hasSingleBean(CommandGateway::class.java)
                creationCount.get().assert().isEqualTo(1)
            }
    }

    @Test
    fun `should select command bus by original parameter name`() {
        val commandBus = mockk<CommandBus>(relaxed = true)
        val otherCommandBus = mockk<CommandBus>(relaxed = true)
        contextRunner
            .enableWow()
            .withBean(CommandWaitNotifier::class.java, { mockk<CommandWaitNotifier>() })
            .withBean(HostAddressSupplier::class.java, { LocalHostAddressSupplier.INSTANCE })
            .withBean("commandBus", CommandBus::class.java, { commandBus })
            .withBean("otherCommandBus", CommandBus::class.java, { otherCommandBus })
            .withUserConfiguration(
                CommandAutoConfiguration::class.java,
                CommandGatewayAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasNotFailed()
                    .hasSingleBean(CommandGateway::class.java)
                context.getBean(CommandGateway::class.java).close()
                verify(exactly = 1) { commandBus.close() }
                verify(exactly = 0) { otherCommandBus.close() }
            }
    }

    @Test
    fun `should load context with command gateway and idempotency checker`() {
        contextRunner
            .enableWow()
            .withBean(CommandWaitNotifier::class.java, { mockk<CommandWaitNotifier>() })
            .withBean(HostAddressSupplier::class.java, { LocalHostAddressSupplier.INSTANCE })
            .withPropertyValues("${CommandProperties.BUS_TYPE}=${BusType.IN_MEMORY_NAME}")
            .withUserConfiguration(
                CommandAutoConfiguration::class.java,
                CommandGatewayAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(AggregateIdempotencyCheckerProvider::class.java)
                    .hasSingleBean(RequestIdChecker::class.java)
                    .hasSingleBean(CommandGateway::class.java)

                context.getBean(AggregateIdempotencyCheckerProvider::class.java)
                    .getChecker(MOCK_AGGREGATE_METADATA)
                    .check(GlobalIdGenerator.generateAsString())
                    .assert().isTrue()
            }
    }

    @Test
    fun `should load local wait notifier and noop idempotency checker when idempotency disabled`() {
        contextRunner
            .enableWow()
            .withClassLoader(FilteredClassLoader("me.ahoo.wow.webflux.route.command.CommandHandlerFunction"))
            .withBean(HostAddressSupplier::class.java, { LocalHostAddressSupplier.INSTANCE })
            .withPropertyValues(
                "${CommandProperties.BUS_TYPE}=${BusType.IN_MEMORY_NAME}",
                "${IdempotencyProperties.PREFIX}.enabled=false",
            )
            .withUserConfiguration(
                CommandAutoConfiguration::class.java,
                CommandGatewayAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(AggregateIdempotencyCheckerProvider::class.java)
                    .hasSingleBean(RequestIdChecker::class.java)
                    .hasSingleBean(CommandWaitNotifier::class.java)

                context.getBean(CommandWaitNotifier::class.java)
                    .assert().isInstanceOf(LocalCommandWaitNotifier::class.java)
                context.getBean(AggregateIdempotencyCheckerProvider::class.java)
                    .getChecker(MOCK_AGGREGATE_METADATA)
                    .check("request-id")
                    .assert().isTrue()
            }
    }

    @Test
    fun `request id checker should use provided request id existence checker`() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("aggregate-id")
        val existenceChecks = AtomicInteger()
        contextRunner
            .enableWow()
            .withBean(CommandWaitNotifier::class.java, { mockk<CommandWaitNotifier>() })
            .withBean(HostAddressSupplier::class.java, { LocalHostAddressSupplier.INSTANCE })
            .withBean(
                AggregateIdempotencyCheckerProvider::class.java,
                {
                    DefaultAggregateIdempotencyCheckerProvider {
                        IdempotencyChecker { false }
                    }
                },
            )
            .withBean(
                RequestIdExistenceChecker::class.java,
                {
                    RequestIdExistenceChecker { checkedAggregateId, requestId ->
                        checkedAggregateId.assert().isEqualTo(aggregateId)
                        requestId.assert().isEqualTo("request-id")
                        existenceChecks.incrementAndGet()
                        Mono.just(false)
                    }
                },
            )
            .withPropertyValues("${CommandProperties.BUS_TYPE}=${BusType.IN_MEMORY_NAME}")
            .withUserConfiguration(
                CommandAutoConfiguration::class.java,
                CommandGatewayAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(RequestIdChecker::class.java)
                    .hasSingleBean(RequestIdExistenceChecker::class.java)

                StepVerifier.create(context.getBean(RequestIdChecker::class.java).check(aggregateId, "request-id"))
                    .expectNext(true)
                    .verifyComplete()
                existenceChecks.get().assert().isEqualTo(1)
            }
    }
}

@AutoConfiguration(after = [CommandGatewayAutoConfiguration::class])
internal class LateCommandBusAutoConfiguration {
    @Bean
    fun lateCommandBus(): CommandBus = InMemoryCommandBus()
}
