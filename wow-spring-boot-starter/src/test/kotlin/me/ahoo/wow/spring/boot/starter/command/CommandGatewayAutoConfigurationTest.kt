package me.ahoo.wow.spring.boot.starter.command

import io.mockk.mockk
import me.ahoo.cosid.machine.HostAddressSupplier
import me.ahoo.cosid.machine.LocalHostAddressSupplier
import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.infra.idempotency.AggregateIdempotencyCheckerProvider
import me.ahoo.wow.spring.boot.starter.BusType
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import reactor.kotlin.test.test

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
                    .hasSingleBean(CommandGateway::class.java)

                context.getBean(AggregateIdempotencyCheckerProvider::class.java)
                    .getChecker(MOCK_AGGREGATE_METADATA)
                    .check(GlobalIdGenerator.generateAsString())
                    .test()
                    .expectNext(true)
                    .verifyComplete()
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
                    .hasSingleBean(CommandWaitNotifier::class.java)

                context.getBean(CommandWaitNotifier::class.java)
                    .assert().isInstanceOf(LocalCommandWaitNotifier::class.java)
                context.getBean(AggregateIdempotencyCheckerProvider::class.java)
                    .getChecker(MOCK_AGGREGATE_METADATA)
                    .check("request-id")
                    .test()
                    .expectNext(true)
                    .verifyComplete()
            }
    }
}
