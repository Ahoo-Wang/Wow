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

package me.ahoo.wow.tck.metrics

import io.micrometer.core.instrument.logging.LoggingMeterRegistry
import io.micrometer.core.instrument.logging.LoggingRegistryConfig
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.dispatcher.DomainEventHandler
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStrategy
import me.ahoo.wow.eventsourcing.snapshot.dispatcher.SnapshotHandler
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.messaging.MessageBus
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.metrics.metered
import me.ahoo.wow.modeling.command.dispatcher.CommandHandler
import me.ahoo.wow.projection.ProjectionHandler
import me.ahoo.wow.saga.stateless.StatelessSagaHandler
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.time.Duration
import kotlin.reflect.jvm.isAccessible

object LoggingMeterRegistryInitializer : BeforeAllCallback {
    val loggingMeterRegistry: LoggingMeterRegistry
    private val publishCallable = LoggingMeterRegistry::class.members.first { it.name == "publish" }.apply {
        isAccessible = true
    }

    init {
        val loggingRegistryConfig = object : LoggingRegistryConfig {
            override fun get(key: String): String? = null
            override fun step(): Duration {
                return Duration.ofSeconds(1)
            }
        }
        loggingMeterRegistry = LoggingMeterRegistry.builder(loggingRegistryConfig).build()
    }

    fun publishMeters() {
        publishCallable.call(loggingMeterRegistry)
    }

    override fun beforeAll(context: ExtensionContext) {
        //
    }
}

private val TCK_METRICS = WowMetrics(LoggingMeterRegistryInitializer.loggingMeterRegistry)

private fun source(component: Any): String =
    component::class.qualifiedName ?: component::class.simpleName ?: "tck"

fun CommandBus.meteredForTck(): CommandBus = metered(TCK_METRICS, source(this))

fun DomainEventBus.meteredForTck(): DomainEventBus = metered(TCK_METRICS, source(this))

fun StateEventBus.meteredForTck(): StateEventBus = metered(TCK_METRICS, source(this))

fun EventStore.meteredForTck(): EventStore = metered(TCK_METRICS, source(this))

fun SnapshotStore.meteredForTck(): SnapshotStore = metered(TCK_METRICS, source(this))

fun SnapshotStrategy.meteredForTck(): SnapshotStrategy = metered(TCK_METRICS, source(this))

fun CommandHandler.meteredForTck(): CommandHandler = metered(TCK_METRICS, source(this))

fun SnapshotHandler.meteredForTck(): SnapshotHandler = metered(TCK_METRICS, source(this))

fun DomainEventHandler.meteredForTck(): DomainEventHandler = metered(TCK_METRICS, source(this))

fun StatelessSagaHandler.meteredForTck(): StatelessSagaHandler = metered(TCK_METRICS, source(this))

fun ProjectionHandler.meteredForTck(): ProjectionHandler = metered(TCK_METRICS, source(this))

@Suppress("UNCHECKED_CAST")
fun <M : Message<*, *>, E : MessageExchange<*, M>, BUS : MessageBus<M, E>> BUS.meteredForTck(): BUS =
    me.ahoo.wow.metrics.MetricDecoratorFactory(TCK_METRICS).decorate(
        component = this,
        source = source(this),
    ) as BUS
