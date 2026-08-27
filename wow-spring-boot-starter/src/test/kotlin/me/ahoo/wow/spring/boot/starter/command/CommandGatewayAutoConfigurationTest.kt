package me.ahoo.wow.spring.boot.starter.command

import io.mockk.mockk
import me.ahoo.cosid.machine.HostAddressSupplier
import me.ahoo.cosid.machine.LocalHostAddressSupplier
import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.RequestIdChecker
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.eventsourcing.RequestIdExistenceChecker
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.infra.idempotency.AggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.IdempotencyChecker
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.spring.boot.starter.BusType
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.util.concurrent.atomic.AtomicInteger

class CommandGatewayAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

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
