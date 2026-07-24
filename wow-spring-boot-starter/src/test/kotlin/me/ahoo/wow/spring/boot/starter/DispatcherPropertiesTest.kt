/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.spring.boot.starter

import me.ahoo.test.asserts.assert
import me.ahoo.wow.messaging.dispatcher.MessageParallelism
import me.ahoo.wow.spring.boot.starter.command.CommandDispatcherProperties
import me.ahoo.wow.spring.boot.starter.event.EventDispatcherProperties
import me.ahoo.wow.spring.boot.starter.projection.ProjectionDispatcherProperties
import me.ahoo.wow.spring.boot.starter.saga.StatelessSagaDispatcherProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import reactor.core.scheduler.Schedulers

internal class DispatcherPropertiesTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(DispatcherPropertiesConfiguration::class.java)

    @Test
    fun `should preserve dispatcher defaults`() {
        listOf(
            CommandDispatcherProperties().let { it.stripeCount to it.schedulerPoolSize },
            EventDispatcherProperties().let { it.stripeCount to it.schedulerPoolSize },
            ProjectionDispatcherProperties().let { it.stripeCount to it.schedulerPoolSize },
            StatelessSagaDispatcherProperties().let { it.stripeCount to it.schedulerPoolSize },
        ).forEach { (stripeCount, schedulerPoolSize) ->
            stripeCount.assert().isEqualTo(MessageParallelism.DEFAULT_PARALLELISM)
            schedulerPoolSize.assert().isEqualTo(Schedulers.DEFAULT_POOL_SIZE)
        }
    }

    @Test
    fun `should reject non-positive dispatcher values`() {
        listOf<() -> Unit>(
            { CommandDispatcherProperties(stripeCount = 0) },
            { EventDispatcherProperties(stripeCount = 0) },
            { ProjectionDispatcherProperties(stripeCount = 0) },
            { StatelessSagaDispatcherProperties(stripeCount = 0) },
        ).forEach { createProperties ->
            val error = assertThrows<IllegalArgumentException> {
                createProperties()
            }
            error.message.assert().isEqualTo("stripeCount must be greater than 0.")
        }

        listOf<() -> Unit>(
            { CommandDispatcherProperties(schedulerPoolSize = 0) },
            { EventDispatcherProperties(schedulerPoolSize = 0) },
            { ProjectionDispatcherProperties(schedulerPoolSize = 0) },
            { StatelessSagaDispatcherProperties(schedulerPoolSize = 0) },
        ).forEach { createProperties ->
            val error = assertThrows<IllegalArgumentException> {
                createProperties()
            }
            error.message.assert().isEqualTo("schedulerPoolSize must be greater than 0.")
        }
    }

    @Test
    fun `should create scheduler supplier with configured pool size`() {
        val supplier = createSchedulerSupplier(
            name = "TestDispatcher",
            stripeCount = 101,
            schedulerPoolSize = 3,
        )

        supplier.name.assert().isEqualTo("TestDispatcher")
        supplier.parallelism.assert().isEqualTo(3)
    }

    @Test
    fun `should bind each dispatcher role independently`() {
        contextRunner
            .withPropertyValues(
                "${CommandDispatcherProperties.PREFIX}.stripe-count=101",
                "${CommandDispatcherProperties.PREFIX}.scheduler-pool-size=2",
                "${EventDispatcherProperties.PREFIX}.stripe-count=102",
                "${EventDispatcherProperties.PREFIX}.scheduler-pool-size=3",
                "${ProjectionDispatcherProperties.PREFIX}.stripe-count=103",
                "${ProjectionDispatcherProperties.PREFIX}.scheduler-pool-size=4",
                "${StatelessSagaDispatcherProperties.PREFIX}.stripe-count=104",
                "${StatelessSagaDispatcherProperties.PREFIX}.scheduler-pool-size=5",
            )
            .run { context ->
                context.getBean(CommandDispatcherProperties::class.java).let {
                    assertValues(it.stripeCount, it.schedulerPoolSize, 101, 2)
                }
                context.getBean(EventDispatcherProperties::class.java).let {
                    assertValues(it.stripeCount, it.schedulerPoolSize, 102, 3)
                }
                context.getBean(ProjectionDispatcherProperties::class.java).let {
                    assertValues(it.stripeCount, it.schedulerPoolSize, 103, 4)
                }
                context.getBean(StatelessSagaDispatcherProperties::class.java).let {
                    assertValues(it.stripeCount, it.schedulerPoolSize, 104, 5)
                }
            }
    }

    @Test
    fun `should fail binding non-positive dispatcher values`() {
        listOf(
            "${CommandDispatcherProperties.PREFIX}.stripe-count" to "stripeCount",
            "${CommandDispatcherProperties.PREFIX}.scheduler-pool-size" to "schedulerPoolSize",
            "${EventDispatcherProperties.PREFIX}.stripe-count" to "stripeCount",
            "${EventDispatcherProperties.PREFIX}.scheduler-pool-size" to "schedulerPoolSize",
            "${ProjectionDispatcherProperties.PREFIX}.stripe-count" to "stripeCount",
            "${ProjectionDispatcherProperties.PREFIX}.scheduler-pool-size" to "schedulerPoolSize",
            "${StatelessSagaDispatcherProperties.PREFIX}.stripe-count" to "stripeCount",
            "${StatelessSagaDispatcherProperties.PREFIX}.scheduler-pool-size" to "schedulerPoolSize",
        ).forEach { (propertyName, fieldName) ->
            contextRunner
                .withPropertyValues("$propertyName=0")
                .run { context ->
                    context.startupFailure.assert().isNotNull()
                    context.startupFailure.causeChainMessages().assert()
                        .contains("$fieldName must be greater than 0.")
                }
        }
    }

    private fun assertValues(
        stripeCount: Int,
        schedulerPoolSize: Int,
        expectedStripeCount: Int,
        expectedSchedulerPoolSize: Int,
    ) {
        stripeCount.assert().isEqualTo(expectedStripeCount)
        schedulerPoolSize.assert().isEqualTo(expectedSchedulerPoolSize)
    }

    private fun Throwable?.causeChainMessages(): String =
        generateSequence(this) { it.cause }
            .mapNotNull { it.message }
            .joinToString("\n")
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
    CommandDispatcherProperties::class,
    EventDispatcherProperties::class,
    ProjectionDispatcherProperties::class,
    StatelessSagaDispatcherProperties::class,
)
private class DispatcherPropertiesConfiguration
