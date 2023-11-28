package me.ahoo.wow.spring.boot.starter.command

import io.mockk.mockk
import me.ahoo.cosid.machine.HostAddressSupplier
import me.ahoo.cosid.machine.LocalHostAddressSupplier
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.infra.idempotency.IdempotencyChecker
import me.ahoo.wow.spring.boot.starter.BusType
import me.ahoo.wow.spring.boot.starter.enableWow
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class CommandGatewayAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
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
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(IdempotencyChecker::class.java)
                    .hasSingleBean(CommandGateway::class.java)
            }
    }
}
